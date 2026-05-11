#!/bin/bash

# Spanish
sed -i -e '/<\/resources>/i\
    <string name="history_filter_date_custom">Rango personalizado...</string>\
    <string name="history_filter_status_format">Estado: %1$s</string>\
    <string name="history_filter_date_format">Fecha: %1$s</string>\
    <string name="history_filter_date_range_format">%1$s - %2$s</string>\
    <string name="history_filter_date_picker_title">Seleccionar rango</string>' app/src/main/res/values-es/strings_history.xml

# Japanese
sed -i -e '/<\/resources>/i\
    <string name="history_filter_date_custom">カスタム範囲...</string>\
    <string name="history_filter_status_format">ステータス: %1$s</string>\
    <string name="history_filter_date_format">日付: %1$s</string>\
    <string name="history_filter_date_range_format">%1$s - %2$s</string>\
    <string name="history_filter_date_picker_title">日付範囲を選択</string>' app/src/main/res/values-ja/strings_history.xml

# Korean
sed -i -e '/<\/resources>/i\
    <string name="history_filter_date_custom">사용자 지정 범위...</string>\
    <string name="history_filter_status_format">상태: %1$s</string>\
    <string name="history_filter_date_format">날짜: %1$s</string>\
    <string name="history_filter_date_range_format">%1$s - %2$s</string>\
    <string name="history_filter_date_picker_title">날짜 범위 선택</string>' app/src/main/res/values-ko/strings_history.xml

# Portuguese
sed -i -e '/<\/resources>/i\
    <string name="history_filter_date_custom">Intervalo personalizado...</string>\
    <string name="history_filter_status_format">Status: %1$s</string>\
    <string name="history_filter_date_format">Data: %1$s</string>\
    <string name="history_filter_date_range_format">%1$s - %2$s</string>\
    <string name="history_filter_date_picker_title">Selecionar período</string>' app/src/main/res/values-pt/strings_history.xml
