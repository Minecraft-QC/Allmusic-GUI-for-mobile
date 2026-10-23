# 构建全部三个版本（1.21.1 / 26.1.2 / 26.3），产物在各目录 build/libs/
# 用法: .\build-all.ps1 [-Task build|compileJava]
param([string]$Task = "build")

# 优先找 JDK 25（26.x 需要）；找不到则依赖 PATH 里的 java
$jdk25 = Get-ChildItem "C:\Program Files\Java\jdk-25*","$env:USERPROFILE\.jdks\jdk-25*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk25) { $env:JAVA_HOME = $jdk25.FullName }

# gradle 命令：优先各工程 gradlew.bat；wrapper 下载失败时回退到本机已解压的 gradle 9.7.1
$localGradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1\bin\gradle.bat"

function Invoke-Gradle([string]$dir) {
	$env:GRADLE_OPTS = "-Dorg.gradle.jvmargs=-Xmx2G"
	$gradlew = Join-Path $dir "gradlew.bat"
	if (Test-Path $gradlew) {
		Push-Location $dir
		& cmd /c $gradlew $Task --offline 2>&1 | Select-Object -Last 5
		$code = $LASTEXITCODE
		Pop-Location
		if ($code -eq 0) { return $true }
		Write-Host "gradlew 失败(exit=$code)，尝试本机 gradle..." -ForegroundColor Yellow
	}
	if (Test-Path $localGradle) {
		Push-Location $dir
		& cmd /c $localGradle $Task --offline 2>&1 | Select-Object -Last 5
		$code = $LASTEXITCODE
		Pop-Location
		return ($code -eq 0)
	}
	return $false
}

$fail = @()
foreach ($d in @("1.21.1", "26.1.2", "26.3")) {
	Write-Host "`n========== $d ==========" -ForegroundColor Cyan
	$dir = Join-Path $PSScriptRoot "AllMusic-GUI\$d"
	if (Invoke-Gradle $dir) {
		Write-Host "[OK] $d" -ForegroundColor Green
	} else {
		Write-Host "[FAIL] $d" -ForegroundColor Red
		$fail += $d
	}
}

if ($fail.Count -gt 0) { Write-Host "`n失败: $($fail -join ', ')" -ForegroundColor Red; exit 1 }
Write-Host "`n全部构建成功" -ForegroundColor Green; exit 0
