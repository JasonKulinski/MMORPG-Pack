@echo off
title Minecraft Server
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install Java 25 from https://adoptium.net and run this again.
  pause & exit /b
)

set JAR=
for %%f in (paper*.jar) do set JAR=%%f
if "%JAR%"=="" (
  echo No paper jar found. Download it from https://papermc.io/downloads/paper into this folder.
  pause & exit /b
)

if not exist "plugins\worldedit*.jar" echo Warning: no WorldEdit jar in the plugins folder.

findstr /c:"eula=true" eula.txt >nul 2>nul
if errorlevel 1 (
  echo Open eula.txt, read the Minecraft EULA, and change eula=false to eula=true to accept it.
  pause & exit /b
)

java -Xms2G -Xmx4G -jar "%JAR%" nogui
pause
