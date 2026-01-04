# auto_install_click.py
import uiautomator2 as u2
import time
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s")

# Connect via USB
d = u2.connect()
logging.info(f"Connected to device: {d.info.get('productName', 'unknown')}")

# Keywords - order matters: more specific first
INSTALL_KEYS = [
    "重新安装",      # "Reinstall"
    "继续安装",      # OPPO/ColorOS - "Continue Install"
    "仍要安装",      # "Install anyway"
    "安装完成",      # "Install complete"
    "完成",          # "Done"
    "确定",          # "OK"
    "允许",          # "Allow"
    "安装",          # Generic "Install"
    "继续",          # "Continue"
    "下一步",        # "Next"
    "Install",
    "Continue",
    "Done",
    "OK"
]

def click_when_found():
    """Poll once, click if found"""
    for txt in INSTALL_KEYS:
        try:
            btn = d(textContains=txt, clickable=True)
            if btn.exists:
                btn.click()
                logging.info(f"Clicked: [{txt}]")
                time.sleep(1.5)  # Give UI time to react
                return True
        except Exception as e:
            logging.debug(f"Exception clicking [{txt}]: {e}")
            continue
    return False

def watch_and_click(timeout=120):
    """Monitor for timeout seconds, click whenever dialog appears"""
    logging.info(f"Watching for install dialogs for {timeout} seconds...")
    t_end = time.time() + timeout
    clicks = 0
    while time.time() < t_end:
        try:
            if click_when_found():
                clicks += 1
                if clicks > 10:  # Prevent infinite loop
                    logging.warning("Too many clicks, stopping")
                    break
                continue
        except Exception as e:
            logging.debug(f"Exception in watch loop: {e}")
        time.sleep(0.3)
    logging.info(f"Watch ended. Total clicks: {clicks}")

if __name__ == "__main__":
    import sys
    timeout = int(sys.argv[1]) if len(sys.argv) > 1 else 120
    watch_and_click(timeout)
