@echo off
setlocal
rem Start from the repository directory, including paths containing spaces.
pushd "%~dp0"
where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven was not found. Install Maven 3.9.x and add its bin directory to PATH.
    popd
    exit /b 1
)
rem compile runs generate-sources first, creating OpenAPI interfaces and models.
call mvn -DskipTests compile spring-boot:run %*
set "SMART_FINANCE_EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %SMART_FINANCE_EXIT_CODE%
