package com.anthroid.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.anthroid.R;
import com.anthroid.shared.file.FileUtils;
import com.anthroid.shared.termux.crash.TermuxCrashUtils;
import com.anthroid.shared.termux.file.TermuxFileUtils;
import com.anthroid.shared.interact.MessageDialogUtils;
import com.anthroid.shared.logger.Logger;
import com.anthroid.shared.markdown.MarkdownUtils;
import com.anthroid.shared.errors.Error;
import com.anthroid.shared.android.PackageUtils;
import com.anthroid.shared.termux.TermuxConstants;
import com.anthroid.shared.termux.TermuxUtils;
import com.anthroid.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.anthroid.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.anthroid.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.anthroid.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.anthroid.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            // Also check if bin directory exists with essential binaries - if not, force re-extraction
            File binDir = new File(TERMUX_PREFIX_DIR_PATH, "bin");
            File bashBinary = new File(binDir, "bash");
            boolean hasBinaries = binDir.exists() && bashBinary.exists();

            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else if (!hasBinaries) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory exists but bin directory is missing or incomplete. Will re-extract bootstrap.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    // Read file content to check if it's a script that needs path patching
                                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                    int readBytes;
                                    while ((readBytes = zipInput.read(buffer)) != -1) {
                                        baos.write(buffer, 0, readBytes);
                                    }
                                    byte[] fileBytes = baos.toByteArray();

                                    // Check if this is a text/script file by looking for shebang or text content
                                    // Scripts need com.termux paths replaced with com.anthroid
                                    boolean isTextFile = false;
                                    if (fileBytes.length > 2 && fileBytes[0] == '#' && fileBytes[1] == '!') {
                                        isTextFile = true; // Shebang script
                                    } else if (zipEntryName.startsWith("etc/") ||
                                               (zipEntryName.startsWith("bin/") && fileBytes.length < 65536 && !isElfBinary(fileBytes))) {
                                        isTextFile = true; // Config files or small non-ELF bin files
                                    }

                                    if (isTextFile) {
                                        // Replace com.termux paths with com.anthroid in text files
                                        String content = new String(fileBytes, "UTF-8");
                                        if (content.contains("com.termux")) {
                                            content = content.replace("com.termux", "com.anthroid");
                                            fileBytes = content.getBytes("UTF-8");
                                        }
                                    }

                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        outStream.write(fileBytes);
                                    }

                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Create compatibility symlink for ELF binaries that have hardcoded com.termux paths
                    // /data/data/com.anthroid/files/data/data/com.termux -> /data/data/com.anthroid
                    // This allows binaries with embedded /data/data/com.termux/files/usr paths to work
                    try {
                        String termuxFilesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;  // /data/data/com.anthroid/files
                        File compatDir = new File(termuxFilesDir, "data/data");
                        if (!compatDir.exists()) {
                            compatDir.mkdirs();
                        }
                        File termuxSymlink = new File(compatDir, "com.termux");
                        if (!termuxSymlink.exists()) {
                            // Symlink: /data/data/com.anthroid/files/data/data/com.termux -> /data/data/com.anthroid
                            Os.symlink("/data/data/com.anthroid", termuxSymlink.getAbsolutePath());
                            Logger.logInfo(LOG_TAG, "Created compatibility symlink for com.termux paths: " + termuxSymlink.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Failed to create compatibility symlink: " + e.getMessage());
                    }

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    // Create set_wrapper utility script for easy Claude CLI configuration
                    ensureSetWrapperScript(activity);

                    // Install Claude Code CLI from assets
                    installClaudeCode(activity);

                    // Create first-boot script to install default packages (openssh, etc.)
                    createFirstBootScript();

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.anthroid" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.anthroid" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }


    /**
     * Creates the set_wrapper utility script in $PREFIX/bin/ for easy Claude CLI configuration.
     * Only creates if it doesn't already exist to preserve user configuration.
     * Usage: set_wrapper <base_url> <auth_token> <model>
     */

    /**
     * Installs Claude Code CLI from bundled assets to the correct location.
     * Creates the node_modules directory structure and copies cli.js, etc.
     */
    public static void installClaudeCode(Context context) {
        try {
            String claudeCodeDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules/@anthropic-ai/claude-code";
            File targetDir = new File(claudeCodeDir);

            // Create directory structure
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            // Copy files from assets
            AssetManager assetManager = context.getAssets();
            String[] files = assetManager.list("claude-code");
            if (files != null) {
                for (String filename : files) {
                    java.io.InputStream is = assetManager.open("claude-code/" + filename);
                    File outFile = new File(targetDir, filename);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.close();
                    is.close();

                    // Make cli.js executable
                    if (filename.equals("cli.js")) {
                        Os.chmod(outFile.getAbsolutePath(), 0755);
                    }
                }
            }

            // Create claude wrapper script in bin
            File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            File claudeWrapper = new File(binDir, "claude");
            String wrapperContent = "#!/data/data/com.anthroid/files/usr/bin/bash\n" +
                "exec /data/data/com.anthroid/files/usr/bin/node /data/data/com.anthroid/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js \"$@\"\n";
            FileOutputStream fos = new FileOutputStream(claudeWrapper);
            fos.write(wrapperContent.getBytes("UTF-8"));
            fos.close();
            Os.chmod(claudeWrapper.getAbsolutePath(), 0755);

            Logger.logInfo(LOG_TAG, "Installed Claude Code CLI to " + claudeCodeDir);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to install Claude Code CLI: " + e.getMessage());
        }
    }

    public static void ensureSetWrapperScript(Context context) {
        try {
            File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            
            // Skip if bin directory doesn't exist (bootstrap not yet extracted)
            if (!binDir.exists()) {
                Logger.logInfo(LOG_TAG, "bin directory doesn't exist yet, skipping set_wrapper creation");
                return;
            }
            
            File setWrapperFile = new File(binDir, "set_wrapper");

            // Skip if file already exists to preserve user configuration
            if (setWrapperFile.exists()) {
                Logger.logInfo(LOG_TAG, "set_wrapper script already exists, skipping");
                return;
            }

            // Read script content from assets
            java.io.InputStream is = context.getAssets().open("set_wrapper.sh");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            // Write to bin directory
            FileOutputStream fos = new FileOutputStream(setWrapperFile);
            fos.write(buffer);
            fos.close();

            Os.chmod(setWrapperFile.getAbsolutePath(), 0700);
            Logger.logInfo(LOG_TAG, "Created set_wrapper script at " + setWrapperFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to create set_wrapper script: " + e.getMessage());
        }
    }

    private static void createFirstBootScript() {
        try {
            File homeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            File firstBootScript = new File(homeDir, ".first_boot.sh");

            // Create the first-boot script that downloads and installs packages locally
            // instead of using pkg which has unreliable mirrors
            StringBuilder sb = new StringBuilder();
            sb.append("#!/data/data/com.anthroid/files/usr/bin/bash\n");
            sb.append("# First-boot script - downloads and installs packages locally\n");
            sb.append("# This script runs once and deletes itself\n");
            sb.append("\n");
            sb.append("export PREFIX=/data/data/com.anthroid/files/usr\n");
            sb.append("export PATH=$PREFIX/bin:$PATH\n");
            sb.append("export HOME=/data/data/com.anthroid/files/home\n");
            sb.append("export TMPDIR=$HOME/tmp\n");
            sb.append("mkdir -p $TMPDIR\n");
            sb.append("\n");
            sb.append("MIRROR=\"https://packages.termux.dev/apt/termux-main\"\n");
            sb.append("ARCH=\"aarch64\"\n");
            sb.append("\n");
            sb.append("download_and_install() {\n");
            sb.append("    local pkg=$1\n");
            sb.append("    echo \"Downloading $pkg...\"\n");
            sb.append("    # Get package info from Packages file\n");
            sb.append("    local debfile=$(curl -sL \"$MIRROR/dists/stable/main/binary-$ARCH/Packages\" | grep -A20 \"^Package: $pkg\\$\" | grep \"^Filename:\" | head -1 | cut -d' ' -f2)\n");
            sb.append("    if [ -n \"$debfile\" ]; then\n");
            sb.append("        curl -sL \"$MIRROR/$debfile\" -o \"$TMPDIR/$pkg.deb\"\n");
            sb.append("        dpkg -i \"$TMPDIR/$pkg.deb\" 2>/dev/null || true\n");
            sb.append("        rm -f \"$TMPDIR/$pkg.deb\"\n");
            sb.append("    else\n");
            sb.append("        echo \"Package $pkg not found\"\n");
            sb.append("    fi\n");
            sb.append("}\n");
            sb.append("\n");
            sb.append("echo 'Installing default packages (download method)...'\n");
            sb.append("\n");
            sb.append("# Install git, sshpass and their key dependencies\n");
            sb.append("for pkg in zlib openssl libnghttp2 libnghttp3 libc-ares curl pcre2 git sshpass; do\n");
            sb.append("    download_and_install $pkg\n");
            sb.append("done\n");
            sb.append("\n");
            sb.append("# Clean up\n");
            sb.append("rm -rf $TMPDIR/*.deb\n");
            sb.append("\n");
            sb.append("# Remove this script after execution\n");
            sb.append("rm -f ~/.first_boot.sh\n");
            sb.append("sed -i '/first_boot/d' ~/.bashrc\n");
            sb.append("echo 'Default packages installed.'\n");

            try (FileOutputStream fos = new FileOutputStream(firstBootScript)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }
            Os.chmod(firstBootScript.getAbsolutePath(), 0700);

            // Add hook to .bashrc to run this script on first terminal session
            File bashrc = new File(homeDir, ".bashrc");
            String bashrcContent = "";
            if (bashrc.exists()) {
                bashrcContent = new String(java.nio.file.Files.readAllBytes(bashrc.toPath()), "UTF-8");
            }

            if (!bashrcContent.contains(".first_boot.sh")) {
                String hookLine = "\n# Run first-boot script if exists\n[ -f ~/.first_boot.sh ] && ~/.first_boot.sh\n";
                try (FileOutputStream fos = new FileOutputStream(bashrc, true)) {
                    fos.write(hookLine.getBytes("UTF-8"));
                }
            }

            Logger.logInfo(LOG_TAG, "Created first-boot script for default packages");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to create first-boot script: " + e.getMessage());
        }
    }

public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    /**
     * Check if byte array starts with ELF magic bytes (0x7f 'E' 'L' 'F')
     */
    private static boolean isElfBinary(byte[] data) {
        return data.length >= 4 &&
               data[0] == 0x7f &&
               data[1] == 'E' &&
               data[2] == 'L' &&
               data[3] == 'F';
    }

}
