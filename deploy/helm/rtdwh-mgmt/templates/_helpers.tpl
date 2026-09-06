{{- define "rtdwh-mgmt.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "rtdwh-mgmt.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "rtdwh-mgmt.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "rtdwh-mgmt.labels" -}}
app.kubernetes.io/name: {{ include "rtdwh-mgmt.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "rtdwh-mgmt.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "rtdwh-mgmt.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "rtdwh-mgmt.secretName" -}}
{{- if .Values.backend.secret.create }}
{{- printf "%s-secret" (include "rtdwh-mgmt.fullname" .) }}
{{- else }}
{{- required "backend.secret.existingSecret is required when secret.create=false" .Values.backend.secret.existingSecret }}
{{- end }}
{{- end }}
