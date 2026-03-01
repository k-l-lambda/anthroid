"""
Auto-click through OPPO Android install dialogs via uiautomator2.

Handles three dialog stages when oppoguardelf is disabled:
  1. "重新安装" / "取消" — reinstall confirmation
  2. Permissions detail page — "安装" button (may need coordinate click)
  3. "安装完成" — post-install with "打开应用" / "完成"

Also handles oppoguardelf-present dialogs: "继续安装", "仍要安装", etc.

Usage:
  python auto_install_click.py [timeout_seconds] [--open]
    --open: click "打开应用" after install (default: click "完成")
"""
import uiautomator2 as u2
import time
import logging
import sys

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s")

d = u2.connect()
logging.info(f"Connected to device: {d.info.get('productName', 'unknown')}")

# Screen dimensions for coordinate-based fallback
screen_info = d.info
SCREEN_W = screen_info.get("displayWidth", 1080)
SCREEN_H = screen_info.get("displayHeight", 2340)

# --- Stage 1: Confirmation dialog ---
STAGE1_KEYS = [
    "重新安装",     # Native packageinstaller (guardelf disabled)
    "继续安装",     # OPPO guardelf dialog
    "仍要安装",     # OPPO guardelf dialog
]

# --- Stage 2: Permissions detail page ---
# The green "安装" button on the permissions page is a custom View that
# uiautomator2 cannot find by text. Fall back to coordinate click.
# Button is at approximately (center_x, screen_h - 280) based on captures.

# --- Stage 3: Post-install ---
STAGE3_KEYS = [
    "打开应用",     # Open app
    "完成",         # Done
]

# --- Generic fallback keys (for any stage) ---
GENERIC_KEYS = [
    "确定",
    "允许",
    "OK",
    "Done",
    "Install",
    "Re-install",
    "Continue",
]

PKG_INSTALLER = "com.android.packageinstaller"


def is_installer_visible():
    """Check if the package installer is the foreground activity."""
    try:
        elems = d(packageName=PKG_INSTALLER)
        return elems.exists
    except Exception:
        return False


def try_click_text(keys):
    """Try to click the first matching button from a list of text keys."""
    for txt in keys:
        try:
            btn = d(textContains=txt, clickable=True)
            if btn.exists:
                btn.click()
                logging.info(f"Clicked: [{txt}]")
                time.sleep(1.5)
                return txt
        except Exception:
            continue
    return None


def try_click_install_button():
    """
    Try to click the green "安装" button on the permissions detail page.
    This button is rendered as a custom View that uiautomator2 can't find by text.
    Strategy: first try text match, then fall back to coordinate click.
    """
    # Try text match first (works on some devices/ROM versions)
    btn = d(text="安装", clickable=True, packageName=PKG_INSTALLER)
    if btn.exists:
        btn.click()
        logging.info("Clicked: [安装] (text match)")
        return True

    # Check if we're on the permissions detail page (has "退出安装" button)
    exit_btn = d(textContains="退出安装", clickable=True)
    if exit_btn.exists:
        # The "安装" button is above "退出安装", roughly at:
        # x = center of screen, y = screen_height - 280 (for 2340h screens)
        # Adjust proportionally for different screen sizes
        install_y = int(SCREEN_H * 0.89)  # ~89% from top
        install_x = SCREEN_W // 2
        d.click(install_x, install_y)
        logging.info(f"Clicked: [安装] (coordinate: {install_x}, {install_y})")
        return True

    return False


def watch_and_click(timeout=120, open_app=False):
    logging.info(f"Watching for install dialogs for {timeout}s (open_app={open_app})...")
    t_end = time.time() + timeout
    clicks = 0
    stage = "waiting"  # waiting → stage1 → stage2 → stage3 → done
    installed = False

    while time.time() < t_end:
        if not is_installer_visible():
            if installed:
                logging.info("Installer dismissed — install complete")
                break
            time.sleep(0.5)
            continue

        # Stage 1: Confirmation dialog
        clicked = try_click_text(STAGE1_KEYS)
        if clicked:
            clicks += 1
            stage = "stage2"
            time.sleep(2)  # Wait for permissions page to load
            continue

        # Stage 2: Permissions detail page — try the green install button
        if try_click_install_button():
            clicks += 1
            stage = "stage3"
            time.sleep(3)  # Wait for installation to complete
            continue

        # Stage 3: Post-install
        # Check for "安装完成" text as indicator
        if d(textContains="安装完成").exists:
            installed = True
            target = "打开应用" if open_app else "完成"
            btn = d(text=target, clickable=True)
            if btn.exists:
                btn.click()
                logging.info(f"Clicked: [{target}]")
                clicks += 1
                break
            # Fallback to any post-install button
            clicked = try_click_text(STAGE3_KEYS)
            if clicked:
                clicks += 1
                break

        # Generic fallback
        clicked = try_click_text(GENERIC_KEYS)
        if clicked:
            clicks += 1

        time.sleep(0.3)

    logging.info(f"Done. Total clicks: {clicks}, installed: {installed}")
    return installed


if __name__ == "__main__":
    timeout = 120
    open_app = False

    args = sys.argv[1:]
    for arg in args:
        if arg == "--open":
            open_app = True
        elif arg.isdigit():
            timeout = int(arg)

    watch_and_click(timeout, open_app)
