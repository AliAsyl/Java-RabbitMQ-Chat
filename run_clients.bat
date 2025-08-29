@echo off

start "broker" rabbitmq-server

timeout /t 3 >nul

set /p COUNT=Ile instancji klienta chcesz uruchomić? (np. 2): 
for /f "delims=0123456789" %%A in ("%COUNT%") do (
  echo "%COUNT%" nie jest poprawną liczbą.
  exit /b 1
)

set CLASSES=classes\production\Chat
set LIB=lib\amqp-client-5.25.0.jar;lib\slf4j-api-1.7.36.jar;lib\slf4j-simple-1.7.36.jar
set CP=%CLASSES%;%LIB%

for /L %%i in (1,1,%COUNT%) do (
  start "" javaw -cp "%CP%" ChatClient
  timeout /t 1 >nul
)
