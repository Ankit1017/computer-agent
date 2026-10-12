@echo off
REM Execute the fat JAR and pass any command line arguments
java -jar "%~dp0target\computer-agent-1.0.0-SNAPSHOT.jar" %*
