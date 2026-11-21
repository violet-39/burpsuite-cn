@echo off
rem 重新构建 burpsuite-cn.jar
rem 依赖：JDK 8+（javac/jar 在 PATH 中），lib\asm-9.11.1.jar
setlocal
cd /d "%~dp0"

if exist out rmdir /s /q out
mkdir out

javac -encoding UTF-8 --release 8 -nowarn -cp lib\asm-9.11.1.jar -d out src\leon406\*.java
if errorlevel 1 goto fail

copy /y res\cn.txt out\ >nul
copy /y res\white.txt out\ >nul

pushd out
jar xf ..\lib\asm-9.11.1.jar
if exist module-info.class del /q module-info.class
if exist META-INF\maven rmdir /s /q META-INF\maven
popd

jar cfm burpsuite-cn.jar manifest.txt -C out .
if errorlevel 1 goto fail

echo.
echo 构建完成: burpsuite-cn.jar
exit /b 0

:fail
echo.
echo 构建失败
exit /b 1
