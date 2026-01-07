import uiautomator2 as u2
import time
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s")

d = u2.connect()
logging.info(f"Connected to device: {d.info.get('productName', 'unknown')}")

INSTALL_KEYS = [
    "继续安装",
    "仍要安装",
    "安装完成",
    "完成",
    "确定",
    "允许",
    "安装",
    "Install",
    "Continue",
    "Done",
    "OK"
]

def click_when_found():
    for txt in INSTALL_KEYS:
        try:
            btn = d(textContains=txt, clickable=True)
            if btn.exists:
                btn.click()
                logging.info(f"Clicked: [{txt}]")
                time.sleep(1.5)
                return True
        except:
            continue
    return False

def watch_and_click(timeout=120):
    logging.info(f"Watching for install dialogs for {timeout}s...")
    t_end = time.time() + timeout
    clicks = 0
    while time.time() < t_end:
        if click_when_found():
            clicks += 1
            if clicks > 10:
                break
        time.sleep(0.3)
    logging.info(f"Done. Total clicks: {clicks}")

if __name__ == "__main__":
    import sys
    timeout = int(sys.argv[1]) if len(sys.argv) > 1 else 120
    watch_and_click(timeout)
