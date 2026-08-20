[CmdletBinding()]
param(
	[string]$BackendBaseUrl = 'http://localhost:8080',
	[string]$StudentId = '0198f000-0000-7000-8000-000000000002',
	[ValidateRange(10, 1800)]
	[int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$resultDirectory = Join-Path $backendRoot 'build\local-problem-generation-e2e'
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
$baseUrl = $BackendBaseUrl.TrimEnd('/')

Write-Host 'Given: 문제 출제 fixture 학생과 배포 AI가 준비되어 있습니다.'
Write-Host 'When 1/4: 배포 AI 진단 왕복을 요청합니다.'
$diagnosis = Invoke-RestMethod -Method Get `
	-Uri "$baseUrl/api/v1/problem-studio/students/$StudentId/weakness-analysis" `
	-TimeoutSec 30
$diagnosis | ConvertTo-Json -Depth 20 | Set-Content `
	-LiteralPath (Join-Path $resultDirectory 'diagnosis.json') -Encoding utf8

if ($diagnosis.diagnosis.status -ne 'GENERATED') {
	throw "AI 진단이 GENERATED가 아닙니다. status=$($diagnosis.diagnosis.status), reason=$($diagnosis.diagnosis.statusReason)"
}

Write-Host 'When 2/4: 진단 ID를 고정해 문제 출제 child 요청을 생성합니다.'
$requestBody = @{
	studentId = $StudentId
	diagnosisId = $diagnosis.diagnosisId
	targets = @(
		@{ areaTag = 'language'; typeTag = 'CONCEPT'; count = 1 },
		@{ areaTag = 'language'; typeTag = 'INFER'; count = 1 }
	)
	difficulty = 'MEDIUM'
} | ConvertTo-Json -Depth 10
$idempotencyKey = 'local-e2e-' + [Guid]::NewGuid().ToString('N')
$created = Invoke-RestMethod -Method Post `
	-Uri "$baseUrl/api/v1/problem-studio/requests" `
	-Headers @{ 'Idempotency-Key' = $idempotencyKey } `
	-ContentType 'application/json; charset=utf-8' `
	-Body $requestBody `
	-TimeoutSec 30

Write-Host "When 3/4: Kafka→Adapter→AI lifecycle을 기다립니다. requestId=$($created.requestId)"
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
	Start-Sleep -Seconds 2
	$status = Invoke-RestMethod -Method Get `
		-Uri "$baseUrl/api/v1/problem-requests/$($created.requestId)" `
		-TimeoutSec 15
	Write-Host "  status=$($status.status)"
	if ($status.status -in @('SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED')) { break }
} while ([DateTime]::UtcNow -lt $deadline)

if ($status.status -notin @('SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED')) {
	throw "${TimeoutSeconds}초 안에 종단 상태에 도달하지 못했습니다. 마지막 상태=$($status.status)"
}
$status | ConvertTo-Json -Depth 30 | Set-Content `
	-LiteralPath (Join-Path $resultDirectory 'request-status.json') -Encoding utf8

if ($status.status -eq 'FAILED') {
	throw "문제 출제가 실패했습니다. errorCode=$($status.errorCode)"
}

Write-Host 'Then 4/4: Backend slot projection과 검토 가능한 결과를 확인합니다.'
$review = Invoke-RestMethod -Method Get `
	-Uri "$baseUrl/api/v1/problem-studio/requests/$($created.requestId)/review" `
	-TimeoutSec 30
$review | ConvertTo-Json -Depth 30 | Set-Content `
	-LiteralPath (Join-Path $resultDirectory 'review.json') -Encoding utf8

$slots = @($review.slots)
$items = @($review.items)
if ($review.requestStatus -ne $status.status) {
	throw "상태 조회와 review의 요청 상태가 다릅니다. status=$($status.status), review=$($review.requestStatus)"
}
if ($review.projectionStatus -notin @('PROJECTED', 'PARTIAL')) {
	throw "검토 가능한 projection 상태가 아닙니다: $($review.projectionStatus)"
}
if ($slots.Count -ne 2) {
	throw "요청한 CONCEPT/INFER 두 slot이 모두 생성되지 않았습니다: $($slots.Count)"
}
$slotIndexes = @($slots | ForEach-Object { [int]$_.slotIndex } | Sort-Object -Unique)
if ($slotIndexes.Count -ne 2 -or $slotIndexes[0] -ne 0 -or $slotIndexes[1] -ne 1) {
	throw "slotIndex가 요청 순서 0,1과 일치하지 않습니다: $($slotIndexes -join ',')"
}
if ($items.Count -lt 1) {
	throw '검토 가능한 문제 item이 한 건도 없습니다.'
}

Write-Host "E2E 성공: status=$($status.status), items=$($items.Count), slots=$($slots.Count)"
Write-Host "결과: $resultDirectory"
