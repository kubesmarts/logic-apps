{{/*
Expand the name of the chart.
*/}}
{{- define "data-index.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "data-index.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "data-index.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "data-index.labels" -}}
helm.sh/chart: {{ include "data-index.chart" . }}
{{ include "data-index.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "data-index.selectorLabels" -}}
app.kubernetes.io/name: {{ include "data-index.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "data-index.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "data-index.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Namespace helper
*/}}
{{- define "data-index.namespace" -}}
{{- if .Values.global.namespace }}
{{- .Values.global.namespace }}
{{- else }}
{{- .Release.Namespace }}
{{- end }}
{{- end }}

{{/*
PostgreSQL connection string
*/}}
{{- define "data-index.postgresql.connectionString" -}}

postgresql://{{ .Values.postgresql.host }}:{{ .Values.postgresql.service.port }}/{{ .Values.postgresql.database }}
{{- end }}

{{/*
Elasticsearch URL
*/}}
{{- define "data-index.elasticsearch.url" -}}
http://elasticsearch.elasticsearch.svc.cluster.local:{{ .Values.elasticsearch.service.port }}
{{- end }}

{{/*
Kafka bootstrap servers
*/}}
{{- define "data-index.kafka.bootstrapServers" -}}
kafka.kafka.svc.cluster.local:{{ .Values.kafka.service.port }}
{{- end }}
