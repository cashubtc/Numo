# GitHub Workflow for Manual Releases

This repository includes a GitHub Actions workflow for creating signed release builds of the Android app. The workflow can only be triggered manually through the GitHub UI.

## Prerequisites

Before using this workflow, you need to set up the following GitHub secrets:

1. `KEYSTORE_BASE64`: Your Android keystore file encoded as a base64 string
2. `KEYSTORE_PASSWORD`: The password for your keystore
3. `KEY_ALIAS`: The alias of the key in your keystore
4. `KEY_PASSWORD`: The password for your key

## How to Encode Your Keystore as Base64

You can encode your keystore file to base64 format using the following command:

```bash
base64 -i your-keystore.jks | tr -d '\n' | pbcopy  # On macOS (copies to clipboard)
# OR
base64 -i your-keystore.jks | tr -d '\n' > keystore-base64.txt  # On Linux/macOS (saves to a file)
```

## Setting Up GitHub Secrets

1. Go to your GitHub repository
2. Navigate to "Settings" > "Secrets and variables" > "Actions"
3. Click "New repository secret"
4. Add each of the required secrets listed above

## Triggering a Release

To create a new release:

1. Go to your GitHub repository
2. Navigate to the "Actions" tab
3. Select the "Manual Release" workflow
4. Click "Run workflow"
5. Fill in the required parameters:
   - Version number (e.g., v1.0.0)
   - Release notes
   - Pre-release status (if needed)
6. Click "Run workflow" to start the build and release process

## Release Artifacts

The workflow will create:
- A signed APK file
- An Android App Bundle (AAB) for Google Play Store
- A GitHub release with these files attached

## Troubleshooting

If the workflow fails, check:
- That all required secrets are properly configured
- The build logs for any compilation or signing errors
- That your keystore is valid and properly encoded in base64
