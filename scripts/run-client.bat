@echo off
cd /d "%~dp0\.."
java -cp target/meshtalk-1.0.0.jar;target/classes;"%USERPROFILE%\.m2\repository\org\openjfx\javafx-controls\21.0.2\*" meshtalk.client.ClientCli %*
