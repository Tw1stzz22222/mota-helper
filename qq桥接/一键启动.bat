@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在启动 QQ<->游戏 桥接脚本...
python mota_bridge.py
pause
