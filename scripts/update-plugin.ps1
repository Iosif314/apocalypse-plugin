# GitHub 릴리스("latest" 태그)에서 최신 Apocalypse.jar를 받아와 이 컴퓨터의 서버 plugins 폴더에 반영한다.
# 이미 받은 버전(jar 에셋 id)과 같으면 아무 작업도 하지 않는다.
# 서버가 켜져 있고 RCON이 활성화돼 있으면 plugman으로 안전하게 언로드 -> 교체 -> 로드한다.
#
# 작업 스케줄러로 돌리면 콘솔 창이 안 보여서 무슨 일이 있었는지 확인할 수 없으므로,
# 실행할 때마다 이 스크립트와 같은 폴더의 update-plugin.log에 결과를 남긴다.
#
# 사용 전 아래 값들을 이 컴퓨터(실제 서버) 환경에 맞게 수정하세요.

$RepoOwner = "Iosif314"
$RepoName = "apocalypse-plugin"
$ServerDir = "C:\Users\USER\Documents\Plugin Test"
$PluginsDir = Join-Path $ServerDir "plugins"
$JarName = "Apocalypse.jar"
$MarkerFile = Join-Path $PSScriptRoot ".last-release-id"
$LogFile = Join-Path $PSScriptRoot "update-plugin.log"

function Write-Log {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    # Windows PowerShell 5.1의 Add-Content 기본 인코딩은 한글이 깨지므로 UTF8로 명시한다.
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

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

Write-Log "===== update-plugin 시작 ====="

try {
    # 0) 서버 경로가 아직 이 컴퓨터에 맞게 수정되지 않았으면 여기서 바로 알아챌 수 있게 확인한다.
    if (-not (Test-Path $ServerDir)) {
        Write-Log "오류: ServerDir 경로가 존재하지 않습니다: $ServerDir (스크립트 위쪽의 `$ServerDir 값을 이 컴퓨터 경로로 수정하세요)"
        exit 1
    }

    # 1) 최신 릴리스 정보 확인
    # 주의: "latest" 태그는 빌드할 때마다 같은 릴리스를 덮어쓰는 방식이라 release.id 자체는 항상 동일하다.
    # 그래서 버전이 바뀌었는지는 릴리스 id가 아니라, 빌드마다 다시 올라오면서 실제로 바뀌는
    # "jar 에셋 자신의 id"로 비교해야 한다.
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$RepoOwner/$RepoName/releases/latest" -Headers @{ "User-Agent" = "apocalypse-updater" }

    $asset = $release.assets | Where-Object { $_.name -like "*.jar" } | Select-Object -First 1
    if ($null -eq $asset) {
        Write-Log "오류: 릴리스에서 jar 파일을 찾지 못했습니다."
        exit 1
    }
    $assetId = $asset.id.ToString()

    $lastId = if (Test-Path $MarkerFile) { (Get-Content $MarkerFile -Raw).Trim() } else { "" }
    if ($assetId -eq $lastId) {
        Write-Log "이미 최신 버전입니다. (asset id: $assetId)"
        exit 0
    }

    # 2) jar 다운로드
    $tempJar = Join-Path $env:TEMP "Apocalypse-download.jar"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tempJar -Headers @{ "User-Agent" = "apocalypse-updater" }
    Write-Log "새 버전 다운로드 완료 (asset id: $assetId, 이전: $(if ($lastId) { $lastId } else { '없음' }))"

    $serverRunning = Test-ServerRunning
    $rconEnabled = (Get-ServerProperty -Key "enable-rcon" -Default "false") -eq "true"
    $rconPort = [int](Get-ServerProperty -Key "rcon.port" -Default "25575")
    $rconPassword = Get-ServerProperty -Key "rcon.password" -Default ""
    Write-Log "서버 실행 중: $serverRunning / RCON 활성화: $rconEnabled"

    # 3) 켜져 있으면 언로드 -> 교체 -> 로드, 꺼져 있으면 그냥 교체
    if ($serverRunning -and $rconEnabled) {
        try {
            Send-Rcon -Port $rconPort -Password $rconPassword -Command "plugman unload Apocalypse" | Out-Null
            Write-Log "플러그인 언로드 완료"
        } catch {
            Write-Log "경고: 언로드 실패: $_"
        }
    }

    New-Item -ItemType Directory -Force -Path $PluginsDir | Out-Null
    Copy-Item -Path $tempJar -Destination (Join-Path $PluginsDir $JarName) -Force
    Write-Log "$JarName 교체 완료 ($PluginsDir)"

    if ($serverRunning -and $rconEnabled) {
        try {
            Send-Rcon -Port $rconPort -Password $rconPassword -Command "plugman load Apocalypse" | Out-Null
            Write-Log "플러그인 로드 완료"
        } catch {
            Write-Log "경고: 로드 실패: $_"
        }
    }

    Set-Content -Path $MarkerFile -Value $assetId
    Write-Log "업데이트 완료 (asset id: $assetId)"
} catch {
    Write-Log "오류: 스크립트 실행 중 예외 발생: $_"
    exit 1
} finally {
    Write-Log "===== update-plugin 종료 ====="
}
