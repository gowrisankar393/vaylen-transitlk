@echo off
echo Starting TransitLK Web Prototype...

REM Start the Flask API Server for AI Models in a new minimized window
start /min cmd /c "echo Starting AI Model Server (Flask)... && python app.py"

REM Give it a second to boot up
timeout /t 2 /nobreak > nul

echo Opening the Local File Server...
start http://localhost:8080

REM Start the simple HTTP server for the frontend files
python -m http.server 8080
pause
