package com.electricdreams.numo.feature.reporting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IssueReportUiState(
    val description: String = "",
    val status: Status = Status.IDLE,
    val validationError: IssueReportValidationResult.Invalid? = null,
    val pendingSubmission: IssueReportSubmission? = null,
    val acceptedRelayCount: Int = 0
) {
    enum class Status {
        IDLE,
        SUBMITTING,
        SENT,
        FAILED,
        UNAVAILABLE
    }
}

class IssueReportViewModel(
    private val builder: IssueReportBuilder = IssueReportBuilder(),
    private val publisher: IssueReportPublisher = IssueReportPublisher(
        OkHttpIssueReportRelayTransport()
    )
) : ViewModel() {
    private val mutableState = MutableStateFlow(IssueReportUiState())
    val state: StateFlow<IssueReportUiState> = mutableState.asStateFlow()

    fun markUnavailable() {
        mutableState.update { current ->
            current.copy(status = IssueReportUiState.Status.UNAVAILABLE)
        }
    }

    fun onDescriptionChanged(description: String) {
        mutableState.update { current ->
            if (description == current.description) {
                current
            } else {
                current.copy(
                    description = description,
                    status = if (current.status == IssueReportUiState.Status.UNAVAILABLE) {
                        IssueReportUiState.Status.UNAVAILABLE
                    } else {
                        IssueReportUiState.Status.IDLE
                    },
                    validationError = null,
                    pendingSubmission = null,
                    acceptedRelayCount = 0
                )
            }
        }
    }

    fun submit(input: IssueReportInput, configuration: IssueReportConfiguration) {
        if (mutableState.value.status == IssueReportUiState.Status.SUBMITTING) return

        val validatedConfiguration = configuration.validate().getOrElse {
            markUnavailable()
            return
        }
        when (val validation = IssueReportValidator.validate(input)) {
            is IssueReportValidationResult.Invalid -> {
                mutableState.update { current ->
                    current.copy(validationError = validation, status = IssueReportUiState.Status.IDLE)
                }
                return
            }
            is IssueReportValidationResult.Valid -> Unit
        }

        mutableState.update { current ->
            current.copy(
                description = input.description,
                status = IssueReportUiState.Status.SUBMITTING,
                validationError = null
            )
        }
        viewModelScope.launch {
            val currentPending = mutableState.value.pendingSubmission
            val submissionResult = if (currentPending != null) {
                Result.success(currentPending)
            } else {
                withContext(Dispatchers.Default) {
                    builder.build(input)
                }
            }
            val submission = submissionResult.getOrElse {
                mutableState.update { current ->
                    current.copy(status = IssueReportUiState.Status.FAILED)
                }
                return@launch
            }
            mutableState.update { current -> current.copy(pendingSubmission = submission) }

            val result = withContext(Dispatchers.IO) {
                publisher.publish(submission, validatedConfiguration)
            }
            if (result.accepted) {
                mutableState.value = IssueReportUiState(
                    status = IssueReportUiState.Status.SENT,
                    acceptedRelayCount = result.acceptedCount
                )
            } else {
                mutableState.update { current ->
                    current.copy(status = IssueReportUiState.Status.FAILED)
                }
            }
        }
    }
}
