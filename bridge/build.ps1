<#
Builds the Java bridge and proves the local patches actually made it into the jar.

Why this exists rather than "run gradlew build":

ViaBedrock applies the `net.raphimc.class-token-replacer` plugin, whose task does not rerun when its
input classes change. Edit a ViaBedrock source file and Gradle will happily recompile it, report
BUILD SUCCESSFUL, and package the *previous* build's classes — so the jar you deploy silently does
not contain your change, and nothing anywhere says so. That cost a deployment: a tab-list patch was
written, built, "verified" by booting the jar, and shipped, and the branding was still there.

So: wipe the stage that lies, build, then read the finished jar back and check the patches are in it.

Usage:  .\build.ps1  [-TrustStore <path to a truststore, if TLS is intercepted here>]
                     [-Offline]  same purpose, no truststore needed, but only on a warm cache
#>
[CmdletBinding()]
param(
    [string] $TrustStore,
    [switch] $Offline,
    [string] $JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$viaBedrock = Join-Path $root "src\ViaBedrock"
$viaProxy = Join-Path $root "src\ViaProxy"
$dist = Join-Path $root "dist\ViaProxy.jar"

$env:JAVA_HOME = $JavaHome
# Resolving dependencies is the only step here that needs the network, and on a machine whose TLS is
# intercepted it is also the only step that fails (PKIX path building). A truststore fixes that
# properly; -Offline sidesteps it by not looking at all, which is enough once the cache already holds
# these versions -- and means an unrelated change does not have to stop for certificate plumbing.
$networkArgs = if ($Offline) { @("--offline") } else { @() }
if ($TrustStore) {
    $env:GRADLE_OPTS = "-Djavax.net.ssl.trustStore=$TrustStore -Djavax.net.ssl.trustStorePassword=changeit"
}

# Each entry is: the class that should carry the patch, a marker that must be present, and a marker
# that must be gone. Add a row whenever a patch lands, so a silently-stale jar fails here instead of
# in production.
$expectations = @(
    @{ Class   = "net/raphimc/viabedrock/protocol/packet/JoinPackets.class"
       Present = "endstone.bridge.tabList"
       Absent  = "github.com/RaphiMC/ViaBedrock"
       What    = "tab list branding removed / configurable" },
    @{ Class   = "net/raphimc/viaproxy/proxy/external_interface/EndstoneBridgeAuth.class"
       Present = "ep_secret"
       Absent  = $null
       What    = "bridge login carries the real IP and shared secret" },
    @{ Class   = "net/raphimc/viabedrock/api/util/BedrockLineBreaks.class"
       Present = "endstone.bridge.traceText"
       Absent  = $null
       What    = "line-break normaliser present" },
    @{ Class   = "net/raphimc/viabedrock/protocol/rewriter/blockentity/SignBlockEntityRewriter.class"
       Present = "BedrockLineBreaks"
       Absent  = $null
       What    = "signs split on every encoding of a line break" },
    @{ Class   = "net/raphimc/viabedrock/protocol/packet/HudPackets.class"
       Present = "endstone.bridge.titleLineSeparator"
       Absent  = $null
       What    = "title/subtitle/action bar breaks collapsed for single-line Java surfaces" },
    @{ Class   = "net/raphimc/viabedrock/protocol/packet/ResourcePackPackets.class"
       Present = "deferring the stack-finished reply"
       Absent  = $null
       What    = "pack handshake is not ended early by a fake-accepted pack" },
    @{ Class   = "net/raphimc/viabedrock/api/model/container/ContainerClickTranslator.class"
       Present = "quickMove"
       Absent  = $null
       What    = "java inventory clicks become bedrock item stack requests" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/ItemStackRequestTracker.class"
       Present = "handleResponse"
       Absent  = $null
       What    = "item stack requests are tracked, predicted and rolled back on rejection" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/InventoryTracker.class"
       Present = "synchroniseDirtyContainers"
       Absent  = $null
       What    = "inventory changes reach the client whichever packet carried them" },
    @{ Class   = "net/raphimc/viabedrock/protocol/packet/InventoryPackets.class"
       Present = "ITEM_STACK_RESPONSE"
       Absent  = $null
       What    = "the server's item stack responses are applied instead of dropped" },
    @{ Class   = "net/raphimc/viabedrock/protocol/packet/ClientPlayerPackets.class"
       Present = "ItemStackRequestTracker"
       Absent  = $null
       What    = "dropping an item goes through the server-authoritative path" },
    @{ Class   = "net/raphimc/viabedrock/experimental/types/inventory/InventoryTransactionPacketType.class"
       Present = "Expected InventoryActionData"
       Absent  = $null
       What    = "server-sent inventory transactions are read with the action list's presence boolean" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/InventoryTracker.class"
       Present = "announceInventoryScreen"
       Absent  = $null
       What    = "the player's own inventory screen is announced, and not waited on forever" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/ChunkTracker.class"
       Present = "reportUnrecognisedBlockEntities"
       Absent  = $null
       What    = "a chunk's block entities that could not be placed are named rather than dropped in silence" },
    @{ Class   = "net/raphimc/viabedrock/api/model/container/ContainerClickTranslator.class"
       Present = "translateCreativeSlot"
       Absent  = $null
       What    = "a creative client's own inventory works, which sends slot contents and never a click" },
    @{ Class   = "net/raphimc/viabedrock/protocol/rewriter/blockentity/ChestBlockEntityRewriter.class"
       Present = "pairedHalf"
       Absent  = $null
       What    = "a double chest renders as one, not two singles side by side" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/InventoryTracker.class"
       Present = "flushCreativeSlotUpdates"
       Absent  = $null
       What    = "a creative shift-click works whichever order its two slot changes arrive in" },
    @{ Class   = "net/raphimc/viabedrock/api/util/CraftingDataReader.class"
       Present = "Unknown CraftingDataType"
       Absent  = $null
       What    = "the server's recipe list is read instead of dropped" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/RecipeStorage.class"
       Present = "crafting_table"
       Absent  = $null
       What    = "crafting table recipes are held and matched against the grid" },
    @{ Class   = "net/raphimc/viabedrock/api/model/container/CraftingContainer.class"
       Present = "CreatedOutputContainer"
       Absent  = $null
       What    = "a crafting table opens as a java window over the player ui container's slots" },
    @{ Class   = "net/raphimc/viabedrock/api/model/container/ContainerClickTranslator.class"
       Present = "distributeIntoInventory"
       Absent  = $null
       What    = "taking a crafting result sends a craft request, not a move" },
    @{ Class   = "net/raphimc/viabedrock/protocol/storage/InventoryTracker.class"
       Present = "refreshCraftingResult"
       Absent  = $null
       What    = "the crafting preview bedrock never sends is computed here" },
    @{ Class   = "net/raphimc/viabedrock/protocol/model/ItemStackRequestSlot.class"
       Present = "PRODUCED_BY_THIS_REQUEST"
       Absent  = $null
       What    = "a crafted stack is named by the request that makes it, not by a zero id" }
)

function Invoke-Gradle([string] $directory, [string[]] $gradleArgs) {
    Push-Location $directory
    try {
        # Gradle writes ordinary notes ("Some input files use or override a deprecated API") and the
        # class-downgrader's chatter to stderr. With $ErrorActionPreference = 'Stop', PowerShell turns
        # any native stderr line into a terminating error, so a perfectly successful build dies with
        # NativeCommandError. The exit code is the only honest signal from a native tool.
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & (Join-Path $directory "gradlew.bat") @gradleArgs
        } finally {
            $ErrorActionPreference = $previous
        }
        if ($LASTEXITCODE -ne 0) { throw "gradle $($gradleArgs -join ' ') failed in $directory" }
    } finally {
        Pop-Location
    }
}

Write-Host "== Clearing the stage with the broken up-to-date check (ViaBedrock class-token-replacer)"
Remove-Item -Recurse -Force (Join-Path $viaBedrock "build\classTokenReplacer"), (Join-Path $viaBedrock "build\libs") -ErrorAction SilentlyContinue

Write-Host "== Building ViaBedrock"
Invoke-Gradle $viaBedrock (@("jar") + $networkArgs)

Write-Host "== Building ViaProxy (fat jar)"
# The fat jar's up-to-date check does not notice a changed ViaBedrock artifact either; removing the
# output is what forces it to repackage.
Remove-Item (Join-Path $viaProxy "build\libs\*.jar") -Force -ErrorAction SilentlyContinue
Invoke-Gradle $viaProxy (@("build", "-x", "test") + $networkArgs)

$built = Get-ChildItem (Join-Path $viaProxy "build\libs") -Filter "ViaProxy-*-SNAPSHOT.jar" |
    Where-Object { $_.Name -notlike "*java8*" -and $_.Name -notlike "*sources*" -and $_.Name -notlike "*javadoc*" } |
    Select-Object -First 1
if (-not $built) { throw "No ViaProxy fat jar was produced" }

New-Item -ItemType Directory -Force -Path (Split-Path $dist) | Out-Null
Copy-Item $built.FullName $dist -Force

Write-Host "== Verifying the patches are in $dist"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($dist)
try {
    $failed = $false
    foreach ($expectation in $expectations) {
        $entry = $archive.GetEntry($expectation.Class)
        if (-not $entry) { Write-Host "  FAIL  missing class $($expectation.Class)"; $failed = $true; continue }

        $stream = $entry.Open()
        $memory = New-Object System.IO.MemoryStream
        $stream.CopyTo($memory)
        $stream.Dispose()
        $text = [System.Text.Encoding]::ASCII.GetString($memory.ToArray())
        $memory.Dispose()

        if ($expectation.Present -and $text -notlike "*$($expectation.Present)*") {
            Write-Host "  FAIL  $($expectation.What): '$($expectation.Present)' not found"
            $failed = $true
        } elseif ($expectation.Absent -and $text -like "*$($expectation.Absent)*") {
            Write-Host "  FAIL  $($expectation.What): '$($expectation.Absent)' is still present"
            $failed = $true
        } else {
            Write-Host "  ok    $($expectation.What)"
        }
    }
} finally {
    $archive.Dispose()
}

if ($failed) {
    throw "The built jar does not contain the local patches. It has NOT been staged for deployment."
}

$size = "{0:N1} MB" -f ((Get-Item $dist).Length / 1MB)
Write-Host "== $dist is up to date ($size) and carries every local patch."
