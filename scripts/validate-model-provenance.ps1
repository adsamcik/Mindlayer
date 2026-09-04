<#
.SYNOPSIS
    Validates the checked-in model provenance ledger against runtime manifests.

.DESCRIPTION
    The default check verifies structure, unique artifact identity, SHA/size
    syntax, and exact agreement with the integrity manifests used by model
    delivery. It succeeds while explicitly marked provenance is incomplete.

    -RequireComplete is the deny-by-default release gate: it additionally
    requires releaseReady=true and verified source, conversion, and licence
    evidence for every artifact.
#>
[CmdletBinding()]
param(
    [string]$ManifestPath,
    [switch]$RequireComplete,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepositoryRoot = (Resolve-Path (Join-Path $ScriptDirectory '..')).Path
if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $RepositoryRoot 'docs\models\model-artifact-provenance.json'
}

function Read-JsonFile {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required JSON file is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Assert-Property {
    param(
        [Parameter(Mandatory)][object]$Object,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Owner
    )
    if ($Name -notin $Object.PSObject.Properties.Name) {
        throw "$Owner is missing required property '$Name'."
    }
}

function Get-ArtifactMap {
    param([Parameter(Mandatory)][object[]]$Artifacts)
    $map = @{}
    foreach ($artifact in $Artifacts) {
        Assert-Property $artifact 'id' 'artifact'
        if ($map.ContainsKey($artifact.id)) {
            throw "Duplicate provenance artifact id '$($artifact.id)'."
        }
        $map[$artifact.id] = $artifact
    }
    return $map
}

function Assert-ArtifactPin {
    param(
        [Parameter(Mandatory)][hashtable]$Artifacts,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Filename,
        [Parameter(Mandatory)][string]$Sha256,
        [Nullable[long]]$ByteSize
    )
    if (-not $Artifacts.ContainsKey($Id)) {
        throw "Provenance artifact '$Id' is missing."
    }
    $artifact = $Artifacts[$Id]
    if ($artifact.filename -cne $Filename) {
        throw "Artifact '$Id' filename '$($artifact.filename)' does not match '$Filename'."
    }
    if ($artifact.sha256 -cne $Sha256) {
        throw "Artifact '$Id' SHA-256 does not match its delivery integrity manifest."
    }
    if ($null -ne $ByteSize -and [long]$artifact.byteSize -ne [long]$ByteSize) {
        throw "Artifact '$Id' byte size '$($artifact.byteSize)' does not match '$([long]$ByteSize)'."
    }
}

function Assert-EvidenceBlock {
    param(
        [Parameter(Mandatory)][object]$Block,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$ArtifactId,
        [switch]$Complete
    )
    Assert-Property $Block 'status' "$ArtifactId.$Name"
    if ($Block.status -notin @('verified', 'unverified')) {
        throw "$ArtifactId.$Name.status must be 'verified' or 'unverified'."
    }
    if ($Complete -and $Block.status -ne 'verified') {
        throw "$ArtifactId.$Name is not verified."
    }
}

function Test-ProvenanceManifest {
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$Complete
    )

    $document = Read-JsonFile $Path
    Assert-Property $document 'schemaVersion' 'manifest'
    Assert-Property $document 'releaseReady' 'manifest'
    Assert-Property $document 'artifacts' 'manifest'
    if ([int]$document.schemaVersion -ne 1) {
        throw "Unsupported model provenance schemaVersion '$($document.schemaVersion)'."
    }
    if ($Complete -and -not [bool]$document.releaseReady) {
        throw 'Model provenance release gate is not ready.'
    }

    $artifacts = Get-ArtifactMap @($document.artifacts)
    foreach ($artifact in $artifacts.Values) {
        foreach ($property in @('filename', 'byteSize', 'sha256', 'source', 'conversion', 'license')) {
            Assert-Property $artifact $property "artifact '$($artifact.id)'"
        }
        if ($artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw "Artifact '$($artifact.id)' SHA-256 must be 64 lowercase hexadecimal characters."
        }
        if ([long]$artifact.byteSize -le 0) {
            throw "Artifact '$($artifact.id)' byteSize must be positive."
        }
        Assert-EvidenceBlock $artifact.source 'source' $artifact.id -Complete:$Complete
        Assert-EvidenceBlock $artifact.conversion 'conversion' $artifact.id -Complete:$Complete
        Assert-EvidenceBlock $artifact.license 'license' $artifact.id -Complete:$Complete
    }

    $chat = Read-JsonFile (Join-Path $RepositoryRoot 'gemma_model\src\main\assets\model_integrity.json')
    $part1 = Read-JsonFile (Join-Path $RepositoryRoot 'gemma_model\src\main\assets\gemma_part_1_integrity.json')
    $part2 = Read-JsonFile (Join-Path $RepositoryRoot 'gemma_model_part_2\src\main\assets\gemma_part_2_integrity.json')
    $embedding = Read-JsonFile (Join-Path $RepositoryRoot 'gemma_embed_model\src\main\assets\embedding_model_integrity.json')
    $ocr = Read-JsonFile (Join-Path $RepositoryRoot 'paddleocr_model\src\main\assets\paddleocr_model_integrity.json')

    Assert-ArtifactPin $artifacts 'chat-gemma-4-e2b-runtime' $chat.modelFile $chat.sha256 2588147712
    Assert-ArtifactPin $artifacts 'chat-gemma-4-e2b-part1' $part1.fragmentFile $part1.fragmentSha256 ([long]$part1.fragmentByteSize)
    Assert-ArtifactPin $artifacts 'chat-gemma-4-e2b-part2' $part2.fragmentFile $part2.fragmentSha256 ([long]$part2.fragmentByteSize)
    Assert-ArtifactPin $artifacts 'embedding-weights' $embedding.models[0].filename $embedding.models[0].sha256 195912440
    Assert-ArtifactPin $artifacts 'embedding-tokenizer' $embedding.models[1].filename $embedding.models[1].sha256 4683319
    Assert-ArtifactPin $artifacts 'ocr-detection' $ocr.models[0].filename $ocr.models[0].sha256 4765560
    Assert-ArtifactPin $artifacts 'ocr-recognition' $ocr.models[1].filename $ocr.models[1].sha256 16528048
    Assert-ArtifactPin $artifacts 'ocr-orientation' $ocr.models[2].filename $ocr.models[2].sha256 994632
    Assert-ArtifactPin $artifacts 'ocr-dictionary' $ocr.models[3].filename $ocr.models[3].sha256 74014

    return [pscustomobject][ordered]@{
        artifactCount = $artifacts.Count
        releaseReady = [bool]$document.releaseReady
        completenessRequired = [bool]$Complete
    }
}

$result = Test-ProvenanceManifest -Path $ManifestPath -Complete:$RequireComplete

if ($SelfTest) {
    if ($result.artifactCount -ne 9) {
        throw "Self-test expected 9 artifacts but found $($result.artifactCount)."
    }
    $strictRejected = $false
    try {
        Test-ProvenanceManifest -Path $ManifestPath -Complete | Out-Null
    } catch {
        $strictRejected = $true
    }
    if (-not $strictRejected -and -not $result.releaseReady) {
        throw 'Self-test expected the intentionally incomplete manifest to fail the strict gate.'
    }
}

Write-Host (
    "Model provenance valid: {0} artifacts; releaseReady={1}; requireComplete={2}" -f
        $result.artifactCount,
        $result.releaseReady,
        $result.completenessRequired
)
