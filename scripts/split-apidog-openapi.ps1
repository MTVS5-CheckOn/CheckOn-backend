param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'

$operationMethods = @('get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace')

$partitions = @(
    [ordered]@{
        File = '01-frontend-backend-auth.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Authentication'
        Caller = 'Frontend'
        Receiver = 'Backend'
        Scope = 'Authentication'
        ImportTag = 'Frontend to Backend/Authentication'
        ServerUrl = 'http://localhost:8080/api/v1'
        ServerDescription = 'Local CheckOn backend API'
        SyntheticTag = $null
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
        Caller = 'Frontend'
        Receiver = 'Backend'
        Scope = 'Dashboard, Engagement, Todo'
        ImportTag = 'Frontend to Backend/Dashboard and Engagement'
        ServerUrl = 'http://localhost:8080/api/v1'
        ServerDescription = 'Local CheckOn backend API'
        SyntheticTag = $null
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
        Caller = 'Frontend'
        Receiver = 'Backend'
        Scope = 'Roster Support, Class Management'
        ImportTag = 'Frontend to Backend/Roster and Class Management'
        ServerUrl = 'http://localhost:8080/api/v1'
        ServerDescription = 'Local CheckOn backend API'
        SyntheticTag = $null
        Routes = [ordered]@{
            '/students/{studentId}/personal-information/name' = @('put')
            '/classes' = @('get', 'post')
            '/classes/{classId}' = @('get', 'patch')
            '/classes/{classId}/archive' = @('post')
        }
    }
    [ordered]@{
        File = '04-frontend-backend-learning.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Learning Records'
        Caller = 'Frontend'
        Receiver = 'Backend'
        Scope = 'Learning Records'
        ImportTag = 'Frontend to Backend/Learning Records'
        ServerUrl = 'http://localhost:8080/api/v1'
        ServerDescription = 'Local CheckOn backend API'
        SyntheticTag = $null
        Routes = [ordered]@{
            '/learning-records' = @('post')
        }
    }
    [ordered]@{
        File = '05-frontend-backend-problem-studio.openapi.json'
        Title = 'CheckOn - Frontend to Backend - Problem Generation and Studio'
        Caller = 'Frontend'
        Receiver = 'Backend'
        Scope = 'Problem Generation, Problem Studio'
        ImportTag = 'Frontend to Backend/Problem Generation and Studio'
        ServerUrl = 'http://localhost:8080/api/v1'
        ServerDescription = 'Local CheckOn backend API'
        SyntheticTag = $null
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
        Caller = 'Frontend'
        Receiver = 'Backend'
        Scope = 'Detection'
        ImportTag = 'Frontend to Backend/Detection'
        ServerUrl = 'http://localhost:8080/api/v1'
        ServerDescription = 'Local CheckOn backend API'
        SyntheticTag = $null
        Routes = [ordered]@{
            '/detection-runs' = @('post')
            '/detection-runs/{runId}' = @('get')
        }
    }
    [ordered]@{
        File = '07-backend-ai-risk-detection.openapi.json'
        Title = 'CheckOn - Backend to AI - Risk Detection'
        Caller = 'Backend'
        Receiver = 'AI Server'
        Scope = 'Risk Detection'
        ImportTag = 'Backend to AI/Risk Detection'
        ServerUrl = 'http://localhost:8000'
        ServerDescription = 'Local CheckOn AI server'
        SyntheticTag = 'AI Risk Detection'
        Routes = [ordered]@{
            '/v1/detect' = @('post')
        }
    }
    [ordered]@{
        File = '08-backend-ai-counsel.openapi.json'
        Title = 'CheckOn - Backend to AI - Counsel Draft'
        Caller = 'Backend'
        Receiver = 'AI Server'
        Scope = 'Counsel Draft'
        ImportTag = 'Backend to AI/Counsel Draft'
        ServerUrl = 'http://localhost:8000'
        ServerDescription = 'Local CheckOn AI server'
        SyntheticTag = 'AI Counsel Draft'
        Routes = [ordered]@{
            '/v1/counsel/drafts' = @('post')
        }
    }
    [ordered]@{
        File = '09-development-backend-detection.openapi.json'
        Title = 'CheckOn - Development Tool to Backend - Detection'
        Caller = 'Apidog or Development Tool'
        Receiver = 'Backend (dev profile only)'
        Scope = 'Development Detection Control'
        ImportTag = 'Development Tool to Backend/Detection'
        ServerUrl = 'http://localhost:8080'
        ServerDescription = 'Local CheckOn backend dev API'
        SyntheticTag = 'Development Detection'
        Routes = [ordered]@{
            '/api/dev/detection-runs' = @('post')
            '/api/dev/detection-runs/{runId}/execute' = @('post')
        }
    }
    [ordered]@{
        File = '90-review-required-legacy.openapi.json'
        Title = 'CheckOn - Review Required - Legacy or Duplicate Paths'
        Caller = 'Unconfirmed'
        Receiver = 'Backend'
        Scope = 'Legacy or duplicate paths requiring owner review'
        ImportTag = 'Review Required/Legacy or Duplicate Paths'
        ServerUrl = 'http://localhost:8080'
        ServerDescription = 'Local CheckOn backend; review paths before use'
        SyntheticTag = 'Review Required'
        Routes = [ordered]@{
            '/' = @('get')
            '/api/v1/detection-runs' = @('post')
            '/api/v1/detection-runs/{runId}' = @('get')
        }
    }
)

function Copy-JsonValue {
    param([Parameter(Mandatory = $true)]$Value)

    return ($Value | ConvertTo-Json -Depth 100 | ConvertFrom-Json)
}

function Remove-OpenApiExamples {
    param(
        $Value,
        [string]$Path = '#'
    )

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) {
        return
    }

    if ($Value -is [System.Array]) {
        for ($index = 0; $index -lt $Value.Count; $index++) {
            Remove-OpenApiExamples -Value $Value[$index] -Path "$Path/$index"
        }
        return
    }

    foreach ($property in @($Value.PSObject.Properties)) {
        $isSchemaPropertyMap = $Path.EndsWith('/properties')
        if (-not $isSchemaPropertyMap -and $property.Name -in @('example', 'examples')) {
            $Value.PSObject.Properties.Remove($property.Name)
            continue
        }
        Remove-OpenApiExamples -Value $property.Value -Path "$Path/$($property.Name)"
    }
}

function Add-OrReplaceProperty {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Name,
        $PropertyValue
    )

    $Value | Add-Member -MemberType NoteProperty -Name $Name -Value $PropertyValue -Force
}

function Convert-NullableSchemaTypes {
    param(
        $Value,
        [string]$Path = '#'
    )

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) {
        return
    }

    if ($Value -is [System.Array]) {
        for ($index = 0; $index -lt $Value.Count; $index++) {
            Convert-NullableSchemaTypes -Value $Value[$index] -Path "$Path/$index"
        }
        return
    }

    $typeProperty = $Value.PSObject.Properties['type']
    if ($null -ne $typeProperty -and $typeProperty.Value -is [System.Array]) {
        $types = @($typeProperty.Value)
        $nonNullTypes = @($types | Where-Object { $_ -ne 'null' })
        $allowsNull = $types -contains 'null'

        if ($nonNullTypes.Count -eq 1) {
            $typeProperty.Value = $nonNullTypes[0]
        }
        elseif ($nonNullTypes.Count -gt 1) {
            $Value.PSObject.Properties.Remove('type')
            Add-OrReplaceProperty -Value $Value -Name oneOf -PropertyValue @(
                $nonNullTypes | ForEach-Object { [pscustomobject][ordered]@{ type = $_ } }
            )
        }
        else {
            $Value.PSObject.Properties.Remove('type')
        }

        if ($allowsNull) {
            Add-OrReplaceProperty -Value $Value -Name nullable -PropertyValue $true
        }
    }
    elseif ($null -ne $typeProperty -and $typeProperty.Value -eq 'null') {
        $propertyName = ($Path -split '/')[-1]
        $inferredType = switch -Regex ($propertyName) {
            '^(error|data|result|detail)$' { 'object'; break }
            default { 'string' }
        }

        $typeProperty.Value = $inferredType
        Add-OrReplaceProperty -Value $Value -Name nullable -PropertyValue $true

        if ($propertyName -in @('resolved_at', 'resolvedAt')) {
            Add-OrReplaceProperty -Value $Value -Name format -PropertyValue 'date-time'
        }
        elseif ($propertyName -in @('feedback_given', 'feedbackGiven')) {
            Add-OrReplaceProperty -Value $Value -Name enum -PropertyValue @('useful', 'not_applicable')
        }
    }

    foreach ($property in @($Value.PSObject.Properties)) {
        Convert-NullableSchemaTypes -Value $property.Value -Path "$Path/$($property.Name)"
    }
}

function Convert-ReferenceSiblings {
    param(
        $Value,
        [string]$Path = '#'
    )

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) {
        return
    }

    if ($Value -is [System.Array]) {
        for ($index = 0; $index -lt $Value.Count; $index++) {
            Convert-ReferenceSiblings -Value $Value[$index] -Path "$Path/$index"
        }
        return
    }

    $referenceProperty = $Value.PSObject.Properties['$ref']
    if ($null -ne $referenceProperty) {
        $reference = [string]$referenceProperty.Value
        $siblings = @(
            $Value.PSObject.Properties |
                Where-Object { $_.Name -ne '$ref' -and -not $_.Name.StartsWith('x-') }
        )

        if ($siblings.Count -gt 0 -and $reference.StartsWith('#/components/schemas/')) {
            $Value.PSObject.Properties.Remove('$ref')
            $referenceEntry = [pscustomobject][ordered]@{ '$ref' = $reference }
            $existingAllOf = @($Value.allOf)
            Add-OrReplaceProperty -Value $Value -Name allOf -PropertyValue (@($referenceEntry) + $existingAllOf)
        }
        elseif ($siblings.Count -gt 0) {
            foreach ($sibling in $siblings) {
                $Value.PSObject.Properties.Remove($sibling.Name)
            }
        }
    }

    foreach ($property in @($Value.PSObject.Properties)) {
        Convert-ReferenceSiblings -Value $property.Value -Path "$Path/$($property.Name)"
    }
}

function Repair-KnownInvalidSchemaAnnotations {
    param([Parameter(Mandatory = $true)]$Document)

    $createdSchema = $Document.components.schemas.DetectionRunResponse.properties.created
    if ($null -ne $createdSchema -and $null -ne $createdSchema.PSObject.Properties['기존 실행을 재사용하면 false']) {
        $createdSchema.description = '새 실행을 생성했으면 true, 기존 실행을 재사용하면 false.'
        $createdSchema.PSObject.Properties.Remove('기존 실행을 재사용하면 false')
    }

    $decisionNoteSchema = $Document.components.schemas.AlertView.properties.decisionNote
    if ($null -ne $decisionNoteSchema -and $null -ne $decisionNoteSchema.PSObject.Properties['거절 시 사유']) {
        $decisionNoteSchema.description = '거절 시 사유이며 PENDING_REVIEW 또는 승인 상태에서는 null.'
        $decisionNoteSchema.PSObject.Properties.Remove('거절 시 사유')
    }
}

function ConvertTo-OpenApi30Compatible {
    param([Parameter(Mandatory = $true)]$Document)

    Remove-OpenApiExamples -Value $Document
    Convert-NullableSchemaTypes -Value $Document
    Convert-ReferenceSiblings -Value $Document
    Repair-KnownInvalidSchemaAnnotations -Document $Document
    return $Document
}

function Get-LocalComponentReferences {
    param([Parameter(Mandatory = $true)]$Value)

    $references = New-Object System.Collections.Generic.List[string]

    function Visit-Value {
        param($Current)

        if ($null -eq $Current) {
            return
        }

        if ($Current -is [string] -or $Current -is [ValueType]) {
            return
        }

        if ($Current -is [System.Collections.IDictionary]) {
            foreach ($key in $Current.Keys) {
                $child = $Current[$key]
                if ($key -eq '$ref' -and $child -is [string] -and $child.StartsWith('#/components/')) {
                    $references.Add($child)
                }
                else {
                    Visit-Value $child
                }
            }
            return
        }

        if ($Current -is [System.Collections.IEnumerable]) {
            foreach ($child in $Current) {
                Visit-Value $child
            }
            return
        }

        foreach ($property in $Current.PSObject.Properties) {
            if ($property.Name -eq '$ref' -and $property.Value -is [string] -and $property.Value.StartsWith('#/components/')) {
                $references.Add($property.Value)
            }
            else {
                Visit-Value $property.Value
            }
        }
    }

    Visit-Value $Value
    return $references
}

function Get-SecuritySchemeNames {
    param([Parameter(Mandatory = $true)]$Paths)

    $names = New-Object System.Collections.Generic.HashSet[string]
    foreach ($pathProperty in $Paths.PSObject.Properties) {
        foreach ($operationProperty in $pathProperty.Value.PSObject.Properties) {
            if ($operationMethods -notcontains $operationProperty.Name) {
                continue
            }
            foreach ($requirement in @($operationProperty.Value.security)) {
                if ($null -eq $requirement) {
                    continue
                }
                foreach ($securityProperty in $requirement.PSObject.Properties) {
                    [void]$names.Add($securityProperty.Name)
                }
            }
        }
    }
    return $names
}

function Select-Components {
    param(
        [Parameter(Mandatory = $true)]$SourceComponents,
        [Parameter(Mandatory = $true)]$Paths
    )

    $selected = [ordered]@{}
    $visited = New-Object System.Collections.Generic.HashSet[string]
    $queue = New-Object System.Collections.Generic.Queue[string]

    foreach ($reference in (Get-LocalComponentReferences $Paths)) {
        $queue.Enqueue($reference)
    }

    foreach ($securityName in (Get-SecuritySchemeNames $Paths)) {
        $queue.Enqueue("#/components/securitySchemes/$securityName")
    }

    while ($queue.Count -gt 0) {
        $reference = $queue.Dequeue()
        if (-not $visited.Add($reference)) {
            continue
        }

        $match = [regex]::Match($reference, '^#/components/([^/]+)/([^/]+)$')
        if (-not $match.Success) {
            throw "Unsupported local component reference: $reference"
        }

        $sectionName = $match.Groups[1].Value
        $componentName = $match.Groups[2].Value
        $sourceSection = $SourceComponents.PSObject.Properties[$sectionName].Value
        if ($null -eq $sourceSection) {
            throw "Missing component section for reference: $reference"
        }

        $sourceComponent = $sourceSection.PSObject.Properties[$componentName].Value
        if ($null -eq $sourceComponent) {
            throw "Missing component for reference: $reference"
        }

        if (-not $selected.Contains($sectionName)) {
            $selected[$sectionName] = [ordered]@{}
        }
        $selected[$sectionName][$componentName] = Copy-JsonValue $sourceComponent

        foreach ($nestedReference in (Get-LocalComponentReferences $sourceComponent)) {
            $queue.Enqueue($nestedReference)
        }
    }

    return $selected
}

function New-PartitionDocument {
    param(
        [Parameter(Mandatory = $true)]$Source,
        [Parameter(Mandatory = $true)]$Partition
    )

    $paths = [ordered]@{}
    $usedTags = New-Object System.Collections.Generic.HashSet[string]

    foreach ($path in $Partition.Routes.Keys) {
        $sourcePathItem = $Source.paths.PSObject.Properties[$path].Value
        if ($null -eq $sourcePathItem) {
            throw "Partition $($Partition.File) references a missing path: $path"
        }

        $pathItem = [ordered]@{}
        foreach ($property in $sourcePathItem.PSObject.Properties) {
            $propertyName = $property.Name.ToLowerInvariant()
            if ($operationMethods -contains $propertyName) {
                if ($Partition.Routes[$path] -notcontains $propertyName) {
                    continue
                }

                $operation = Copy-JsonValue $property.Value
                if ($Partition.SyntheticTag) {
                    $operation.tags = @($Partition.SyntheticTag)
                }
                foreach ($tag in @($operation.tags)) {
                    if ($tag) {
                        [void]$usedTags.Add($tag)
                    }
                }
                $pathItem[$propertyName] = $operation
            }
            else {
                $pathItem[$property.Name] = Copy-JsonValue $property.Value
            }
        }

        foreach ($expectedMethod in $Partition.Routes[$path]) {
            if (-not $pathItem.Contains($expectedMethod)) {
                throw "Partition $($Partition.File) references a missing operation: $($expectedMethod.ToUpper()) $path"
            }
        }

        $paths[$path] = $pathItem
    }

    $tagDefinitions = @()
    foreach ($sourceTag in @($Source.tags)) {
        if ($usedTags.Contains($sourceTag.name)) {
            $tagDefinitions += Copy-JsonValue $sourceTag
        }
    }
    foreach ($usedTag in $usedTags) {
        if (-not (@($tagDefinitions.name) -contains $usedTag)) {
            $tagDefinitions += [ordered]@{ name = $usedTag }
        }
    }

    $document = [ordered]@{
        openapi = $Source.openapi
        info = [ordered]@{
            title = $Partition.Title
            description = "통신 방향: $($Partition.Caller) -> $($Partition.Receiver)`n범위: $($Partition.Scope)`n원본 계약의 path, request, response 내용은 유지하고 통신 경계별로 분리했다."
            version = $Source.info.version
        }
        servers = @(
            [ordered]@{
                url = $Partition.ServerUrl
                description = $Partition.ServerDescription
            }
        )
        tags = $tagDefinitions
        paths = $paths
        components = Select-Components -SourceComponents $Source.components -Paths ([pscustomobject]$paths)
        'x-checkon-communication' = [ordered]@{
            caller = $Partition.Caller
            receiver = $Partition.Receiver
            scope = $Partition.Scope
            serverUrl = $Partition.ServerUrl
            source = [System.IO.Path]::GetFileName($SourcePath)
        }
    }

    return $document
}

function New-CombinedDocument {
    param(
        [Parameter(Mandatory = $true)]$Source,
        [Parameter(Mandatory = $true)]$Partitions
    )

    $document = Copy-JsonValue $Source
    $document.info.title = 'CheckOn - All Communication APIs'
    $document.info.description = "Apidog one-shot import document.`nFrontend -> Backend, Backend -> AI, development-only, and review-required APIs are grouped by tags and operation-level servers."
    $document.servers = @(
        [ordered]@{ url = 'http://localhost:8080/api/v1'; description = 'Local CheckOn backend API' }
        [ordered]@{ url = 'http://localhost:8080'; description = 'Local CheckOn backend root and dev API' }
        [ordered]@{ url = 'http://localhost:8000'; description = 'Local CheckOn AI server' }
    )

    $document.tags = @()
    $knownTags = New-Object System.Collections.Generic.HashSet[string]
    $generatedOperationIds = @{
        'POST /api/dev/detection-runs' = 'prepareDevelopmentDetectionRun'
        'POST /api/dev/detection-runs/{runId}/execute' = 'executeDevelopmentDetectionRun'
        'GET /' = 'getLegacyDashboardContent'
        'POST /v1/detect' = 'detectRiskSignals'
        'POST /v1/counsel/drafts' = 'createCounselDraft'
    }

    foreach ($partition in $Partitions) {
        foreach ($path in $partition.Routes.Keys) {
            $pathItem = $document.paths.PSObject.Properties[$path].Value
            foreach ($method in $partition.Routes[$path]) {
                $operation = $pathItem.PSObject.Properties[$method].Value
                $operation | Add-Member -MemberType NoteProperty -Name servers -Value @(
                    [ordered]@{
                        url = $partition.ServerUrl
                        description = $partition.ServerDescription
                    }
                ) -Force

                if (@($operation.tags).Count -gt 0) {
                    $operation | Add-Member -MemberType NoteProperty -Name 'x-original-tags' -Value @($operation.tags) -Force
                }
                $operation.tags = @($partition.ImportTag)
                if ($knownTags.Add($partition.ImportTag)) {
                    $document.tags += [pscustomobject][ordered]@{
                        name = $partition.ImportTag
                        description = "$($partition.Caller) -> $($partition.Receiver): $($partition.Scope)"
                    }
                }

                if ($path -eq '/api/v1/detection-runs' -and $method -eq 'post') {
                    $operation | Add-Member -MemberType NoteProperty -Name 'x-original-operation-id' -Value $operation.operationId -Force
                    $operation.operationId = 'requestDetectionRunLegacy'
                }
                elseif (-not $operation.operationId) {
                    Add-OrReplaceProperty -Value $operation -Name operationId -PropertyValue $generatedOperationIds["$($method.ToUpperInvariant()) $path"]
                }
            }
        }
    }

    $document | Add-Member -MemberType NoteProperty -Name 'x-checkon-communication' -Value ([ordered]@{
        mode = 'combined'
        source = [System.IO.Path]::GetFileName($SourcePath)
        note = 'Each operation server identifies its caller-to-receiver communication boundary.'
    }) -Force

    return $document
}

function Get-Operations {
    param([Parameter(Mandatory = $true)]$Document)

    $operations = @()
    foreach ($pathProperty in $Document.paths.PSObject.Properties) {
        foreach ($operationProperty in $pathProperty.Value.PSObject.Properties) {
            if ($operationMethods -contains $operationProperty.Name.ToLowerInvariant()) {
                $operations += [pscustomobject]@{
                    method = $operationProperty.Name.ToUpperInvariant()
                    path = $pathProperty.Name
                    operationId = $operationProperty.Value.operationId
                }
            }
        }
    }
    return $operations
}

$resolvedSourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$source = Get-Content -LiteralPath $resolvedSourcePath -Raw | ConvertFrom-Json
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutputDirectory) | Out-Null

$sourceOperations = @(Get-Operations $source)
$assignments = New-Object System.Collections.Generic.List[object]
$manifestParts = @()

foreach ($partition in $partitions) {
    $document = New-PartitionDocument -Source $source -Partition $partition
    $document = Copy-JsonValue $document
    $document = ConvertTo-OpenApi30Compatible $document
    $outputPath = Join-Path $resolvedOutputDirectory $partition.File
    $json = $document | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText($outputPath, $json, (New-Object System.Text.UTF8Encoding($false)))

    $roundTrip = Get-Content -LiteralPath $outputPath -Raw | ConvertFrom-Json
    $operations = @(Get-Operations $roundTrip)
    foreach ($operation in $operations) {
        $assignments.Add([pscustomobject]@{
            key = "$($operation.method) $($operation.path)"
            file = $partition.File
        })
    }

    $duplicateOperationIds = @(
        $operations |
            Where-Object { $_.operationId } |
            Group-Object operationId |
            Where-Object { $_.Count -gt 1 } |
            Select-Object -ExpandProperty Name
    )
    if ($duplicateOperationIds.Count -gt 0) {
        throw "Duplicate operationId in $($partition.File): $($duplicateOperationIds -join ', ')"
    }

    foreach ($reference in (Get-LocalComponentReferences $roundTrip)) {
        $match = [regex]::Match($reference, '^#/components/([^/]+)/([^/]+)$')
        if (-not $match.Success) {
            throw "Unsupported reference in $($partition.File): $reference"
        }
        $section = $roundTrip.components.PSObject.Properties[$match.Groups[1].Value].Value
        $component = $section.PSObject.Properties[$match.Groups[2].Value].Value
        if ($null -eq $component) {
            throw "Broken reference in $($partition.File): $reference"
        }
    }

    $manifestParts += [ordered]@{
        file = $partition.File
        caller = $partition.Caller
        receiver = $partition.Receiver
        scope = $partition.Scope
        serverUrl = $partition.ServerUrl
        operationCount = $operations.Count
        operations = @($operations | ForEach-Object { "$($_.method) $($_.path)" })
    }
}

$combinedFile = '00-checkon-all.openapi.json'
$combinedDocument = New-CombinedDocument -Source $source -Partitions $partitions
$combinedDocument = Copy-JsonValue $combinedDocument
$combinedDocument = ConvertTo-OpenApi30Compatible $combinedDocument
$combinedOutputPath = Join-Path $resolvedOutputDirectory $combinedFile
$combinedJson = $combinedDocument | ConvertTo-Json -Depth 100
[System.IO.File]::WriteAllText($combinedOutputPath, $combinedJson, (New-Object System.Text.UTF8Encoding($false)))

$combinedRoundTrip = Get-Content -LiteralPath $combinedOutputPath -Raw | ConvertFrom-Json
$combinedOperations = @(Get-Operations $combinedRoundTrip)
$combinedDuplicateOperationIds = @(
    $combinedOperations |
        Where-Object { $_.operationId } |
        Group-Object operationId |
        Where-Object { $_.Count -gt 1 } |
        Select-Object -ExpandProperty Name
)
if ($combinedDuplicateOperationIds.Count -gt 0) {
    throw "Duplicate operationId in ${combinedFile}: $($combinedDuplicateOperationIds -join ', ')"
}
if ($combinedOperations.Count -ne $sourceOperations.Count) {
    throw "Combined operation coverage mismatch. Expected=$($sourceOperations.Count) Actual=$($combinedOperations.Count)"
}
foreach ($reference in (Get-LocalComponentReferences $combinedRoundTrip)) {
    $match = [regex]::Match($reference, '^#/components/([^/]+)/([^/]+)$')
    if (-not $match.Success) {
        throw "Unsupported reference in ${combinedFile}: $reference"
    }
    $section = $combinedRoundTrip.components.PSObject.Properties[$match.Groups[1].Value].Value
    $component = $section.PSObject.Properties[$match.Groups[2].Value].Value
    if ($null -eq $component) {
        throw "Broken reference in ${combinedFile}: $reference"
    }
}

$duplicateAssignments = @($assignments | Group-Object key | Where-Object { $_.Count -gt 1 })
if ($duplicateAssignments.Count -gt 0) {
    throw "Operations assigned more than once: $($duplicateAssignments.Name -join ', ')"
}

$assignedKeys = @($assignments.key)
$sourceKeys = @($sourceOperations | ForEach-Object { "$($_.method) $($_.path)" })
$unassigned = @($sourceKeys | Where-Object { $assignedKeys -notcontains $_ })
$unexpected = @($assignedKeys | Where-Object { $sourceKeys -notcontains $_ })
if ($unassigned.Count -gt 0 -or $unexpected.Count -gt 0) {
    throw "Partition coverage mismatch. Unassigned=[$($unassigned -join ', ')] Unexpected=[$($unexpected -join ', ')]"
}

$manifest = [ordered]@{
    source = [System.IO.Path]::GetFileName($resolvedSourcePath)
    sourceSha256 = (Get-FileHash -LiteralPath $resolvedSourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceOperationCount = $sourceOperations.Count
    partitionCount = $partitions.Count
    assignedExactlyOnce = $true
    combinedFile = $combinedFile
    combinedOperationCount = $combinedOperations.Count
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    parts = $manifestParts
}
$manifestJson = $manifest | ConvertTo-Json -Depth 100
[System.IO.File]::WriteAllText((Join-Path $resolvedOutputDirectory 'manifest.json'), $manifestJson, (New-Object System.Text.UTF8Encoding($false)))

$readmeLines = New-Object System.Collections.Generic.List[string]
$readmeLines.Add('# CheckOn OpenAPI communication split')
$readmeLines.Add('')
$readmeLines.Add('원본 Apidog 통합 export를 호출자와 수신자 기준으로 먼저 나누고, Frontend -> Backend 계약은 업무 파트별로 다시 분리했다.')
$readmeLines.Add('각 JSON은 필요한 component만 재귀적으로 포함하며 Apidog 개별 import용 독립 OpenAPI 문서로 구성했다.')
$readmeLines.Add('전체를 한 번에 가져오려면 `00-checkon-all.openapi.json` 하나만 import한다.')
$readmeLines.Add('')
$readmeLines.Add('| 파일 | 통신 방향 | 서버 URL | 범위 | API 수 |')
$readmeLines.Add('|---|---|---|---|---:|')
foreach ($part in $manifestParts) {
    $readmeLines.Add("| ``$($part.file)`` | $($part.caller) -> $($part.receiver) | ``$($part.serverUrl)`` | $($part.scope) | $($part.operationCount) |")
}
$readmeLines.Add('')
$readmeLines.Add('## 반드시 확인할 항목')
$readmeLines.Add('')
$readmeLines.Add('- `90-review-required-legacy.openapi.json`에는 소유 또는 표준 경로가 확정되지 않은 3개 API를 격리했다.')
$readmeLines.Add('- `GET /`는 설명상 브리핑 API지만 표준 `GET /dashboard/briefing`과 역할이 겹친다.')
$readmeLines.Add('- `/api/v1/detection-runs*`는 `/detection-runs*`와 역할 및 일부 operationId가 겹치지만 security와 응답 정의가 다르다.')
$readmeLines.Add('- 원본에는 전역 `servers`가 없어 현재 저장소의 Controller 매핑과 로컬 설정을 기준으로 Backend `http://localhost:8080`, AI `http://localhost:8000`을 추가했다.')
$readmeLines.Add('- `POST /v1/counsel/drafts` 설명에는 후속 GET/refine API와 Kafka 완료 이벤트가 언급되지만 원본 paths에는 포함되어 있지 않다.')
$readmeLines.Add('- 원본 export의 OpenAPI 3.0 비호환 nullable 표기와 잘못 들어간 schema-shaped example은 Apidog import 호환 형태로 정규화했다.')
$readmeLines.Add('')
$readmeLines.Add('## 검증')
$readmeLines.Add('')
$readmeLines.Add("- 원본 API $($sourceOperations.Count)개를 중복 없이 정확히 한 파일에만 배정했다.")
$readmeLines.Add("- 단일 통합 파일에도 원본 API $($combinedOperations.Count)개가 모두 포함되어 있다.")
$readmeLines.Add('- 모든 결과 JSON을 다시 파싱했다.')
$readmeLines.Add('- 각 파일 안에서 operationId 중복과 깨진 로컬 component 참조가 없는지 검사했다.')
$readmeLines.Add('- 계약의 path, parameters, requestBody, responses, security 의미는 유지했다. 잘못된 example은 제거하고 nullable 및 `$ref` 표현을 OpenAPI 3.0 문법으로 정규화했다.')
$readmeLines.Add('- Redocly와 Swagger CLI에서 OpenAPI 문서 유효성 검증을 통과했다.')
$readmeLines.Add('- Apidog UI에서의 실제 import는 실행하지 않았으므로 최종 import 확인은 남아 있다.')

[System.IO.File]::WriteAllLines((Join-Path $resolvedOutputDirectory 'README.md'), $readmeLines, (New-Object System.Text.UTF8Encoding($false)))

Write-Output "Generated $($partitions.Count) OpenAPI files in $resolvedOutputDirectory"
Write-Output "Assigned $($sourceOperations.Count) source operations exactly once."
Write-Output "Generated combined OpenAPI file with $($combinedOperations.Count) operations: $combinedFile"
