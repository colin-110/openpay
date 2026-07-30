@ECHO OFF
SETLOCAL

SET BASE_DIR=%~dp0
SET WRAPPER_DIR=%BASE_DIR%.mvn\wrapper
SET MAVEN_VERSION=3.9.9
SET MAVEN_HOME=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%
SET ARCHIVE=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip
SET DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip

IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  IF NOT EXIST "%WRAPPER_DIR%" MKDIR "%WRAPPER_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ARCHIVE%'; Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%WRAPPER_DIR%' -Force"
)

"%MAVEN_HOME%\bin\mvn.cmd" %*
ENDLOCAL
