[CmdletBinding()]
param(
    [string]$SourceUrl = "https://download.geofabrik.de/africa/madagascar-latest.osm.pbf",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$dataDirectory = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot "data\osm"))
$expectedPrefix = $repositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if (-not $dataDirectory.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Le dossier de destination doit rester dans le dépôt."
}

$targetFile = Join-Path $dataDirectory "madagascar-latest.osm.pbf"
$partialFile = "$targetFile.part"
$checksumUrl = "$SourceUrl.md5"

if ((Test-Path -LiteralPath $targetFile) -and -not $Force) {
    Write-Host "Le fichier existe déjà : $targetFile"
    Write-Host "Utilisez -Force pour le remplacer après vérification."
    exit 0
}

New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null

Write-Host "Téléchargement de l'extrait OSM Madagascar…"
if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
    & curl.exe --location --fail --retry 3 --continue-at - --output $partialFile $SourceUrl
    if ($LASTEXITCODE -ne 0) {
        throw "Le téléchargement a échoué. Le fichier partiel est conservé pour une reprise."
    }
} else {
    Invoke-WebRequest -Uri $SourceUrl -OutFile $partialFile
}

Write-Host "Vérification de l'empreinte MD5 publiée par Geofabrik…"
$checksumResponse = Invoke-WebRequest -Uri $checksumUrl
$expectedChecksum = ($checksumResponse.Content -split "\s+")[0].Trim().ToUpperInvariant()
$actualChecksum = (Get-FileHash -LiteralPath $partialFile -Algorithm MD5).Hash.ToUpperInvariant()
if ($actualChecksum -ne $expectedChecksum) {
    Remove-Item -LiteralPath $partialFile -Force
    throw "Empreinte invalide. Attendue : $expectedChecksum ; obtenue : $actualChecksum. Le fichier partiel a été supprimé."
}

Move-Item -LiteralPath $partialFile -Destination $targetFile -Force
Write-Host "Extrait prêt : $targetFile"
Write-Host "MD5 : $actualChecksum"
