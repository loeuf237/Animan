@REM ----------------------------------------------------------------------------
@REM Maven Wrapper startup script for Windows
@REM ----------------------------------------------------------------------------
@IF "%__MVNW_ARG0_NAME__%"=="" (SET "__MVNW_ARG0_NAME__=%~nx0")
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN ("%~dp0.mvn\wrapper\maven-wrapper.properties") DO @(
    IF "%%A"=="wrapperUrl" SET "__MVNW_WRAPPERURL__=%%B"
)
@IF "%__MVNW_WRAPPERURL__%"=="" (
    SET "__MVNW_WRAPPERURL__=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
)
@SET "PSModulePath=%__MVNW_PSMODULEP_SAVE__%"
@SET "MAVEN_PROJECTBASEDIR=%~dp0"

@REM Use spring-boot-maven-plugin through wrapper
@SET "WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar"
@SET "WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain"

@java %MAVEN_OPTS% ^
  -classpath "%WRAPPER_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  %WRAPPER_LAUNCHER% %*

@IF ERRORLEVEL 1 goto error
@goto end

:error
@SET ERROR_CODE=1

:end
@EXIT /B %ERROR_CODE%
