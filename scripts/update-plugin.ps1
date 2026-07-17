# GitHub 릴리스("latest" 태그)에서 최신 Apocalypse.jar를 받아와 이 컴퓨터의 서버 plugins 폴더에 반영한다.
# 이미 받은 버전(release id)과 같으면 아무 작업도 하지 않는다.
# 서버가 켜져 있고 RCON이 활성화돼 있으면 plugman으로 안전하게 언로드 -> 교체 -> 로드한다.
#
# 사용 전 아래 값들을 이 컴퓨터(실제 서버) 환경에 맞게 수정하세요.

$RepoOwner = "Iosif314"
$RepoName = "apocalypse-plugin"
$ServerDir = "C:\Users\USER\Documents\Plugin Test"
$PluginsDir = Join-Path $ServerDir "plugins"
$JarName = "Apocalypse.jar"
$MarkerFile = Join-Path $PSScriptRoot ".last-release-id"

function Get-ServerProperty {
    param([string]$Key, [string]$Default)
    $propsFile = Join-Path $ServerDir "server.properties"
    if (-not (Test-Path $propsFile)) { return $Default }
    $line = Get-Content $propsFile | Where-Object { $_ -like "$Key=*" } | Select-Object -First 1
    if ($null -eq $line) { return $Default }
    return $line.Substring($Key.Length + 1)
}

function Test-ServerRunning {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect("localhost", 25565)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

# Source RCON 프로토콜(build.gradle.kts의 sendRcon과 동일한 프로토콜)을 그대로 구현한다.
function Send-Rcon {
    param([int]$Port, [string]$Password, [string]$Command)

    $client = New-Object System.Net.Sockets.TcpClient("localhost", $Port)
    $client.ReceiveTimeout = 5000
    $client.SendTimeout = 5000
    $stream = $client.GetStream()

    function Write-RconPacket([int]$Id, [int]$Type, [string]$Body) {
        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $length = 4 + 4 + $bodyBytes.Length + 2
        $buffer = New-Object byte[] ($length + 4)
        [System.BitConverter]::GetBytes($length).CopyTo($buffer, 0)
        [System.BitConverter]::GetBytes($Id).CopyTo($buffer, 4)
        [System.BitConverter]::GetBytes($Type).CopyTo($buffer, 8)
        $bodyBytes.CopyTo($buffer, 12)
        $stream.Write($buffer, 0, $buffer.Length)
        $stream.Flush()
    }

    function Read-RconPacket {
        $lenBytes = New-Object byte[] 4
        $stream.Read($lenBytes, 0, 4) | Out-Null
        $len = [System.BitConverter]::ToInt32($lenBytes, 0)
        $data = New-Object byte[] $len
        $offset = 0
        while ($offset -lt $len) {
            $read = $stream.Read($data, $offset, $len - $offset)
            if ($read -le 0) { break }
            $offset += $read
        }
        $id = [System.BitConverter]::ToInt32($data, 0)
        $body = [System.Text.Encoding]::UTF8.GetString($data, 8, $len - 10)
        return @{ Id = $id; Body = $body }
    }

    try {
        Write-RconPacket -Id 1 -Type 3 -Body $Password
        $auth = Read-RconPacket
        if ($auth.Id -eq -1) {
            throw "RCON 인증 실패 (비밀번호 확인)"
        }

        Write-RconPacket -Id 2 -Type 2 -Body $Command
        $response = Read-RconPacket
        return $response.Body
    } finally {
        $client.Close()
    }
}

# 1) 최신 릴리스 정보 확인
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$RepoOwner/$RepoName/releases/latest" -Headers @{ "User-Agent" = "apocalypse-updater" }
$releaseId = $release.id.ToString()

$lastId = if (Test-Path $MarkerFile) { (Get-Content $MarkerFile -Raw).Trim() } else { "" }
if ($releaseId -eq $lastId) {
    Write-Host "[update-plugin] 이미 최신 버전입니다. (release id: $releaseId)"
    exit 0
}

$asset = $release.assets | Where-Object { $_.name -like "*.jar" } | Select-Object -First 1
if ($null -eq $asset) {
    Write-Error "[update-plugin] 릴리스에서 jar 파일을 찾지 못했습니다."
    exit 1
}

# 2) jar 다운로드
$tempJar = Join-Path $env:TEMP "Apocalypse-download.jar"
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tempJar -Headers @{ "User-Agent" = "apocalypse-updater" }

$serverRunning = Test-ServerRunning
$rconEnabled = (Get-ServerProperty -Key "enable-rcon" -Default "false") -eq "true"
$rconPort = [int](Get-ServerProperty -Key "rcon.port" -Default "25575")
$rconPassword = Get-ServerProperty -Key "rcon.password" -Default ""

# 3) 켜져 있으면 언로드 -> 교체 -> 로드, 꺼져 있으면 그냥 교체
if ($serverRunning -and $rconEnabled) {
    try {
        Send-Rcon -Port $rconPort -Password $rconPassword -Command "plugman unload Apocalypse" | Out-Null
        Write-Host "[update-plugin] 플러그인 언로드 완료"
    } catch {
        Write-Warning "[update-plugin] 언로드 실패: $_"
    }
}

New-Item -ItemType Directory -Force -Path $PluginsDir | Out-Null
Copy-Item -Path $tempJar -Destination (Join-Path $PluginsDir $JarName) -Force
Write-Host "[update-plugin] $JarName 교체 완료"

if ($serverRunning -and $rconEnabled) {
    try {
        Send-Rcon -Port $rconPort -Password $rconPassword -Command "plugman load Apocalypse" | Out-Null
        Write-Host "[update-plugin] 플러그인 로드 완료"
    } catch {
        Write-Warning "[update-plugin] 로드 실패: $_"
    }
}

Set-Content -Path $MarkerFile -Value $releaseId
Write-Host "[update-plugin] 업데이트 완료 (release id: $releaseId)"
