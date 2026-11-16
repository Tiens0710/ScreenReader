@echo off
setlocal

set "APP_HOME=%~dp0"
for %%i in ("%APP_HOME%.") do set "APP_HOME=%%~fi"
set "CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"
set "APP_BASE_NAME=%~n0"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

if not exist "%JAVA_EXE%" (
    echo ERROR: JAVA_HOME points to an invalid Java installation: %JAVA_HOME%
    exit /b 1
)

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
