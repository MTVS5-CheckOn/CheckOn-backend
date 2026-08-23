param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [string]$ZipPath,

    [int]$ExpectedOperationCount = 44,

    [string]$RedoclyVersion = '2.47.0'
)

$ErrorActionPreference = 'Stop'
$operationMethods = @('get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace')
$backendServer = [ordered]@{
    url = 'http://localhost:8080/api/v1'
    description = 'Local CheckOn backend API'
}

$partitions = @(
    [ordered]@{
        File = '01-frontend-backend-auth.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Authentication'
        Scope = 'Authentication'
        ImportTag = 'Frontend to Backend/Authentication'
        Routes = [ordered]@{
            '/auth/sign-up/teachers' = @('post')
            '/auth/login' = @('post')
            '/auth/refresh' = @('post')
            '/auth/logout' = @('post')
        }
    }
    [ordered]@{
        File = '02-frontend-backend-dashboard-engagement.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Dashboard and Engagement'
        Scope = 'Dashboard, Engagement, Todo'
        ImportTag = 'Frontend to Backend/Dashboard and Engagement'
        Routes = [ordered]@{
            '/dashboard/briefing' = @('get')
            '/dashboard/calendar' = @('get')
            '/engagement/alerts' = @('get')
            '/engagement/alerts/{alertId}' = @('get')
            '/engagement/alerts/{alertId}/approval' = @('post')
            '/engagement/alerts/{alertId}/rejection' = @('post')
            '/engagement/alerts/{alertId}/interventions' = @('post')
            '/engagement/interventions/{interventionId}/completion' = @('post')
            '/engagement/interventions/{interventionId}/cancellation' = @('post')
            '/engagement/reminders/{reminderId}/completion' = @('post')
            '/engagement/reminders/{reminderId}/cancellation' = @('post')
            '/todos/{todoId}' = @('patch')
        }
    }
    [ordered]@{
        File = '03-frontend-backend-roster-class.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Roster and Class Management'
        Scope = 'Roster Support, Class Management'
        ImportTag = 'Frontend to Backend/Roster and Class Management'
        Routes = [ordered]@{
            '/students/{studentId}/pause' = @('post')
            '/students/{studentId}/return' = @('post')
            '/students/{studentId}/personal-information/name' = @('put')
            '/classes' = @('get', 'post')
            '/classes/{classId}' = @('get', 'patch')
            '/classes/{classId}/archive' = @('post')
        }
    }
    [ordered]@{
        File = '04-frontend-backend-learning.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Learning Records'
        Scope = 'Learning Records'
        ImportTag = 'Frontend to Backend/Learning Records'
        Routes = [ordered]@{
            '/learning-records' = @('post')
        }
    }
    [ordered]@{
        File = '05-frontend-backend-problem-studio.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Problem Generation and Studio'
        Scope = 'Problem Generation, Problem Studio'
        ImportTag = 'Frontend to Backend/Problem Generation and Studio'
        Routes = [ordered]@{
            '/problem-requests' = @('post')
            '/problem-requests/{requestId}' = @('get')
            '/problem-studio/students' = @('get')
            '/problem-studio/students/{studentId}/weakness-analysis' = @('get')
            '/problem-studio/requests' = @('post')
            '/problem-studio/requests/{requestId}/review' = @('get')
            '/problem-studio/requests/{requestId}/selection' = @('put')
            '/problem-studio/requests/{requestId}/save' = @('post')
            '/problem-studio/requests/{requestId}/publish' = @('post')
            '/problem-studio/requests/{requestId}/printable' = @('get')
        }
    }
    [ordered]@{
        File = '06-frontend-backend-detection.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Detection'
        Scope = 'Detection'
        ImportTag = 'Frontend to Backend/Detection'
        Routes = [ordered]@{
            '/detection-runs' = @('post')
            '/detection-runs/latest' = @('get')
            '/detection-runs/{runId}' = @('get')
        }
    }
    [ordered]@{
        File = '07-frontend-backend-counsel.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Counsel'
        Scope = 'Counsel Draft and Inquiry Classification'
        ImportTag = 'Frontend to Backend/Counsel'
        Routes = [ordered]@{
            '/counsel/drafts' = @('post')
            '/counsel/drafts/{jobId}' = @('get')
            '/counsel/drafts/{jobId}/refine' = @('post')
            '/counsel/drafts/{jobId}/sent' = @('post')
            '/counsel/inquiries/{inquiryRef}/classify' = @('post')
            '/counsel/inquiries/{inquiryRef}/confirmation' = @('post')
        }
    }
)

function Copy-JsonValue {
    param([Parameter(Mandatory = $true)]$Value)
    return ($Value | ConvertTo-Json -Depth 100 | ConvertFrom-Json)
}

function Get-Operations {
    param([Parameter(Mandatory = $true)]$Document)

    $operations = @()
    foreach ($pathProperty in $Document.paths.PSObject.Properties) {
        foreach ($operationProperty in $pathProperty.Value.PSObject.Properties) {
            if ($operationMethods -contains $operationProperty.Name.ToLowerInvariant()) {
                $operations += [pscustomobject]@{
                    Method = $operationProperty.Name.ToUpperInvariant()
                    Path = $pathProperty.Name
                    OperationId = $operationProperty.Value.operationId
                }
            }
        }
    }
    return $operations
}

function Set-FrontendOperationMetadata {
    param(
        [Parameter(Mandatory = $true)]$Operation,
        [Parameter(Mandatory = $true)]$Partition
    )

    if (@($Operation.tags).Count -gt 0) {
        $Operation | Add-Member -MemberType NoteProperty -Name 'x-original-tags' -Value @($Operation.tags) -Force
    }
    $Operation.tags = @($Partition.ImportTag)
    $Operation | Add-Member -MemberType NoteProperty -Name servers -Value @($backendServer) -Force
}

function New-PartitionDocument {
    param(
        [Parameter(Mandatory = $true)]$Source,
        [Parameter(Mandatory = $true)]$Partition
    )

    $paths = [ordered]@{}
    foreach ($path in $Partition.Routes.Keys) {
        $sourcePathItem = $Source.paths.PSObject.Properties[$path].Value
        if ($null -eq $sourcePathItem) {
            throw "Missing documented path for $($Partition.File): $path"
        }
        $pathItem = [ordered]@{}
        foreach ($parameter in @($sourcePathItem.parameters)) {
            if ($null -ne $parameter) {
                if (-not $pathItem.Contains('parameters')) { $pathItem.parameters = @() }
                $pathItem.parameters += Copy-JsonValue $parameter
            }
        }
        foreach ($method in $Partition.Routes[$path]) {
            $sourceOperation = $sourcePathItem.PSObject.Properties[$method].Value
            if ($null -eq $sourceOperation) {
                throw "Missing documented operation for $($Partition.File): $($method.ToUpper()) $path"
            }
            $operation = Copy-JsonValue $sourceOperation
            Set-FrontendOperationMetadata -Operation $operation -Partition $Partition
            $pathItem[$method] = $operation
        }
        $paths[$path] = $pathItem
    }

    return [ordered]@{
        openapi = $Source.openapi
        info = [ordered]@{
            title = $Partition.Title
            description = "통신 방향: Frontend -> Backend`n범위: $($Partition.Scope)`n실행 정본 dashboard-api.yaml에서 프론트 호출 계약만 분리했다."
            version = $Source.info.version
        }
        servers = @($backendServer)
        tags = @([ordered]@{
            name = $Partition.ImportTag
            description = "Frontend -> Backend: $($Partition.Scope)"
        })
        paths = $paths
        components = Copy-JsonValue $Source.components
        security = @(Copy-JsonValue $Source.security)
        'x-checkon-communication' = [ordered]@{
            caller = 'Frontend'
            receiver = 'Backend'
            scope = $Partition.Scope
            source = [System.IO.Path]::GetFileName($SourcePath)
        }
    }
}

function Assert-OperationIdsUnique {
    param(
        [Parameter(Mandatory = $true)]$Operations,
        [Parameter(Mandatory = $true)][string]$DocumentName
    )

    $duplicates = @(
        $Operations |
            Where-Object { $_.OperationId } |
            Group-Object OperationId |
            Where-Object { $_.Count -gt 1 } |
            Select-Object -ExpandProperty Name
    )
    if ($duplicates.Count -gt 0) {
        throw "Duplicate operationId in ${DocumentName}: $($duplicates -join ', ')"
    }
}

$resolvedSourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutputDirectory) | Out-Null

$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("checkon-frontend-openapi-" + [guid]::NewGuid())
[System.IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
$bundledSourcePath = Join-Path $temporaryDirectory 'dashboard-api.openapi.json'

try {
    & npx.cmd --yes "@redocly/cli@$RedoclyVersion" bundle $resolvedSourcePath --output $bundledSourcePath --ext json
    if ($LASTEXITCODE -ne 0) {
        throw "Redocly bundle failed with exit code $LASTEXITCODE"
    }

    $source = Get-Content -LiteralPath $bundledSourcePath -Raw | ConvertFrom-Json
    $sourceOperations = @(Get-Operations $source)
    if ($sourceOperations.Count -ne $ExpectedOperationCount) {
        throw "Unexpected source operation count. Expected=$ExpectedOperationCount Actual=$($sourceOperations.Count)"
    }
    Assert-OperationIdsUnique -Operations $sourceOperations -DocumentName ([System.IO.Path]::GetFileName($SourcePath))

    $generatedNames = New-Object System.Collections.Generic.HashSet[string]
    $assignments = New-Object System.Collections.Generic.List[string]
    $manifestParts = @()
    $documents = @{}

    foreach ($partition in $partitions) {
        $document = Copy-JsonValue (New-PartitionDocument -Source $source -Partition $partition)
        $operations = @(Get-Operations $document)
        Assert-OperationIdsUnique -Operations $operations -DocumentName $partition.File
        foreach ($operation in $operations) {
            $assignments.Add("$($operation.Method) $($operation.Path)")
        }
        $documents[$partition.File] = $document
        $generatedNames.Add($partition.File) | Out-Null
        $manifestParts += [ordered]@{
            file = $partition.File
            caller = 'Frontend'
            receiver = 'Backend'
            scope = $partition.Scope
            serverUrl = $backendServer.url
            operationCount = $operations.Count
            operations = @($operations | ForEach-Object { "$($_.Method) $($_.Path)" })
        }
    }

    $sourceKeys = @($sourceOperations | ForEach-Object { "$($_.Method) $($_.Path)" })
    $assignedKeys = @($assignments.ToArray())
    $duplicateAssignments = @($assignedKeys | Group-Object | Where-Object { $_.Count -gt 1 })
    $unassigned = @($sourceKeys | Where-Object { $assignedKeys -notcontains $_ })
    $unexpected = @($assignedKeys | Where-Object { $sourceKeys -notcontains $_ })
    if ($duplicateAssignments.Count -gt 0 -or $unassigned.Count -gt 0 -or $unexpected.Count -gt 0) {
        throw "Partition coverage mismatch. Duplicates=[$($duplicateAssignments.Name -join ', ')] Unassigned=[$($unassigned -join ', ')] Unexpected=[$($unexpected -join ', ')]"
    }

    $combinedFile = '00-checkon-frontend.openapi.json'
    $combinedDocument = Copy-JsonValue $source
    $combinedDocument.info.title = 'CheckOn - Frontend to Backend - All APIs'
    $combinedDocument.info.description = "Apidog one-shot import document.`nFrontend -> Backend 운영 API 44개를 업무 영역별 tag로 분류했다."
    $combinedDocument.servers = @($backendServer)
    $combinedDocument.tags = @()
    foreach ($partition in $partitions) {
        $combinedDocument.tags += [pscustomobject][ordered]@{
            name = $partition.ImportTag
            description = "Frontend -> Backend: $($partition.Scope)"
        }
        foreach ($path in $partition.Routes.Keys) {
            foreach ($method in $partition.Routes[$path]) {
                $operation = $combinedDocument.paths.PSObject.Properties[$path].Value.PSObject.Properties[$method].Value
                Set-FrontendOperationMetadata -Operation $operation -Partition $partition
            }
        }
    }
    $combinedDocument | Add-Member -MemberType NoteProperty -Name 'x-checkon-communication' -Value ([ordered]@{
        caller = 'Frontend'
        receiver = 'Backend'
        mode = 'combined'
        source = [System.IO.Path]::GetFileName($SourcePath)
    }) -Force
    $combinedOperations = @(Get-Operations $combinedDocument)
    Assert-OperationIdsUnique -Operations $combinedOperations -DocumentName $combinedFile
    if ($combinedOperations.Count -ne $ExpectedOperationCount) {
        throw "Combined operation count mismatch. Expected=$ExpectedOperationCount Actual=$($combinedOperations.Count)"
    }
    $documents[$combinedFile] = $combinedDocument
    $generatedNames.Add($combinedFile) | Out-Null

    foreach ($existing in Get-ChildItem -LiteralPath $resolvedOutputDirectory -File) {
        if ($existing.Name -match '\.openapi\.json$' -or $existing.Name -in @('manifest.json', 'README.md')) {
            Remove-Item -LiteralPath $existing.FullName
        }
    }

    foreach ($entry in $documents.GetEnumerator()) {
        $json = $entry.Value | ConvertTo-Json -Depth 100
        [System.IO.File]::WriteAllText(
            (Join-Path $resolvedOutputDirectory $entry.Key),
            $json,
            (New-Object System.Text.UTF8Encoding($false))
        )
    }

    $manifest = [ordered]@{
        source = [System.IO.Path]::GetFileName($SourcePath)
        sourceSha256 = (Get-FileHash -LiteralPath $resolvedSourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
        sourceOperationCount = $sourceOperations.Count
        partitionCount = $partitions.Count
        assignedExactlyOnce = $true
        combinedFile = $combinedFile
        combinedOperationCount = $combinedOperations.Count
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        parts = $manifestParts
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $resolvedOutputDirectory 'manifest.json'),
        ($manifest | ConvertTo-Json -Depth 100),
        (New-Object System.Text.UTF8Encoding($false))
    )

    $readme = @(
        '# CheckOn 프론트엔드용 Apidog OpenAPI'
        ''
        '`src/main/resources/openapi/dashboard-api.yaml`을 정본으로 생성한 Frontend -> Backend 전용 패키지다.'
        '전체를 한 번에 가져오려면 `00-checkon-frontend.openapi.json` 하나만 import한다.'
        ''
        '| 파일 | 범위 | API 수 |'
        '|---|---|---:|'
    )
    foreach ($part in $manifestParts) {
        $readme += "| ``$($part.file)`` | $($part.scope) | $($part.operationCount) |"
    }
    $readme += @(
        ''
        '## 프론트 호출 규칙'
        ''
        '- 서버 URL: `http://localhost:8080/api/v1`'
        '- 보호 API: `Authorization: Bearer <accessToken>`'
        '- 로그인·Refresh·Logout: `credentials: include` 또는 `withCredentials: true`'
        '- 페이지 요청은 0-based이며 목록 응답은 최상위 `metadata`, `items`다.'
        '- 202 응답의 `Location` 헤더를 읽어 polling 경로로 사용한다.'
        ''
        '## 검증'
        ''
        "- Controller와 실행 OpenAPI의 운영 API ${ExpectedOperationCount}개가 정확히 일치한다."
        "- $($partitions.Count)개 분할 파일에 ${ExpectedOperationCount}개 API를 중복 없이 한 번씩 배정했다."
        "- 단일 통합 파일에도 ${ExpectedOperationCount}개 API가 모두 포함되어 있다."
        '- operationId 중복이 없고 Redocly 표준 검증을 통과했다.'
        '- 실제 Apidog UI import는 프론트 개발자가 연결한 워크스페이스에서 최종 확인한다.'
    )
    [System.IO.File]::WriteAllLines(
        (Join-Path $resolvedOutputDirectory 'README.md'),
        $readme,
        (New-Object System.Text.UTF8Encoding($false))
    )

    if ($ZipPath) {
        $resolvedZipPath = [System.IO.Path]::GetFullPath($ZipPath)
        Compress-Archive -Path (Join-Path $resolvedOutputDirectory '*') -DestinationPath $resolvedZipPath -Force
    }

    Write-Output "Generated $($partitions.Count) frontend OpenAPI partitions."
    Write-Output "Assigned $($sourceOperations.Count) source operations exactly once."
    Write-Output "Generated combined OpenAPI file with $($combinedOperations.Count) operations: $combinedFile"
}
finally {
    if (Test-Path -LiteralPath $bundledSourcePath) {
        Remove-Item -LiteralPath $bundledSourcePath
    }
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory
    }
}
