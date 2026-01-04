package com.anthroid.app.activities;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.SwitchPreferenceCompat;
import com.anthroid.vpn.ProxyVpnService;
import com.anthroid.vpn.VpnSettingsHelper;
import tun.proxy.service.Tun2HttpVpnService;
import com.anthroid.R;
import com.anthroid.shared.activities.ReportActivity;
import com.anthroid.shared.file.FileUtils;
import com.anthroid.shared.models.ReportInfo;
import com.anthroid.app.models.UserAction;
import com.anthroid.shared.interact.ShareUtils;
import com.anthroid.shared.android.PackageUtils;
import com.anthroid.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import com.anthroid.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.anthroid.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.anthroid.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.anthroid.shared.android.AndroidUtils;
import com.anthroid.shared.termux.TermuxConstants;
import com.anthroid.shared.termux.TermuxUtils;
import com.anthroid.shared.activity.media.AppCompatActivityUtils;
import com.anthroid.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.anthroid.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        private ActivityResultLauncher<Intent> vpnPermissionLauncher;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            // Register VPN permission launcher
            vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        // Permission granted
                        Toast.makeText(requireContext(), "VPN permission granted", Toast.LENGTH_SHORT).show();
                        updateVpnStatus(requireContext());
                    } else {
                        // Permission denied
                        Toast.makeText(requireContext(), "VPN permission denied", Toast.LENGTH_SHORT).show();
                        updateVpnStatus(requireContext());
                    }
                }
            );
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            // Configure ASR model preference on main thread
            configureAsrModelPreference(context);
            configureVpnProxyPreference(context);

            new Thread() {
                @Override
                public void run() {
                    configureTermuxAPIPreference(context);
                    configureTermuxFloatPreference(context);
                    configureTermuxTaskerPreference(context);
                    configureTermuxWidgetPreference(context);
                    configureAboutPreference(context);
                    configureDonatePreference(context);
                    configureComponentsPreference(context);
                }
            }.start();
        }

        
        
        private void configureVpnProxyPreference(@NonNull Context context) {
            // VPN Permission preference
            Preference vpnPermission = findPreference("vpn_permission");
            if (vpnPermission != null) {
                updateVpnPermissionSummary(context, vpnPermission);
                vpnPermission.setOnPreferenceClickListener(preference -> {
                    Intent prepareIntent = ProxyVpnService.Companion.prepare(context);
                    if (prepareIntent != null) {
                        vpnPermissionLauncher.launch(prepareIntent);
                    } else {
                        Toast.makeText(context, "VPN permission already granted", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }

            // VPN Status preference
            updateVpnStatus(context);

            // Manage Proxies preference
            Preference manageProxies = findPreference("manage_proxies");
            if (manageProxies != null) {
                manageProxies.setOnPreferenceClickListener(preference -> {
                    context.startActivity(new Intent(context, ProxySettingsActivity.class));
                    return true;
                });
            }
        }
        
        private void updateVpnPermissionSummary(@NonNull Context context, Preference pref) {
            boolean granted = ProxyVpnService.Companion.prepare(context) == null;
            pref.setSummary(granted ? "Permission granted (ready for agent tools)" : "Tap to grant VPN permission");
        }
        
        private void updateVpnStatus(@NonNull Context context) {
            // Update VPN status in manage_proxies summary
            Preference manageProxies = findPreference("manage_proxies");
            if (manageProxies != null) {
                boolean socks5Running = ProxyVpnService.Companion.isRunning();
                boolean httpRunning = Tun2HttpVpnService.isRunning();

                if (socks5Running) {
                    manageProxies.setSummary("Running: " + ProxyVpnService.Companion.getProxyInfo());
                } else if (httpRunning) {
                    manageProxies.setSummary("Running: " + Tun2HttpVpnService.getProxyInfo());
                } else {
                    manageProxies.setSummary("Not running");
                }
            }

            Preference vpnPermission = findPreference("vpn_permission");
            if (vpnPermission != null) {
                updateVpnPermissionSummary(context, vpnPermission);
            }
        }

        private void configureTermuxAPIPreference(@NonNull Context context) {
            Preference termuxAPIPreference = findPreference("termux_api");
            if (termuxAPIPreference != null) {
                TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxFloatPreference(@NonNull Context context) {
            Preference termuxFloatPreference = findPreference("termux_float");
            if (termuxFloatPreference != null) {
                TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxTaskerPreference(@NonNull Context context) {
            Preference termuxTaskerPreference = findPreference("termux_tasker");
            if (termuxTaskerPreference != null) {
                TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxWidgetPreference(@NonNull Context context) {
            Preference termuxWidgetPreference = findPreference("termux_widget");
            if (termuxWidgetPreference != null) {
                TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About Anthroid";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append("## ").append(TermuxConstants.ANTHROID_APP_NAME).append("\n\n");
                            aboutString.append(TermuxConstants.ANTHROID_APP_DESCRIPTION).append("\n\n");
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.ANTHROID_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            // Anthroid: Donate feature is disabled
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                donatePreference.setVisible(false);
            }
        }

        private void configureComponentsPreference(@NonNull Context context) {
            Preference componentsPreference = findPreference("components");
            if (componentsPreference != null) {
                componentsPreference.setOnPreferenceClickListener(preference -> {
                    context.startActivity(new Intent(context, ComponentsActivity.class));
                    return true;
                });
            }
        }

        private static final String TAG = "SettingsActivity";
        private static final String ASR_MODEL_URL = "https://github.com/k-l-lambda/anthroid/releases/download/models/sherpa-onnx-sensevoice.tar.bz2";
        private static final String MODEL_DIR_NAME = "sherpa-onnx-sensevoice";
        private ProgressDialog progressDialog;

        private void configureAsrModelPreference(@NonNull Context context) {
            ListPreference asrModelPref = findPreference("asr_model");
            if (asrModelPref == null) return;

            // Update summary based on model installation status
            updateAsrModelSummary(context, asrModelPref);

            asrModelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String value = (String) newValue;
                if ("sensevoice".equals(value)) {
                    File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
                    File modelFile = new File(modelDir, "model.int8.onnx");
                    if (!modelFile.exists()) {
                        // Model not installed, ask to download
                        showDownloadDialog(context, asrModelPref);
                        return false; // Don't change preference yet
                    }
                }
                return true;
            });
        }

        private void updateAsrModelSummary(@NonNull Context context, ListPreference pref) {
            File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
            File modelFile = new File(modelDir, "model.int8.onnx");
            if (modelFile.exists()) {
                pref.setSummary("SenseVoice model installed (239MB)");
            } else {
                pref.setSummary("Select ASR model for voice input");
            }
        }

        private void showDownloadDialog(@NonNull Context context, ListPreference pref) {
            new AlertDialog.Builder(context)
                .setTitle("Download Voice Model")
                .setMessage("SenseVoice model (155MB download, 239MB installed) is required for voice input.\n\nDownload now?")
                .setPositiveButton("Download", (dialog, which) -> {
                    startModelDownload(context, pref);
                })
                .setNegativeButton("Cancel", null)
                .show();
        }

        private void startModelDownload(@NonNull Context context, ListPreference pref) {
            progressDialog = new ProgressDialog(context);
            progressDialog.setTitle("Downloading Voice Model");
            progressDialog.setMessage("Starting download...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMax(100);
            progressDialog.setCancelable(false);
            progressDialog.show();

            new Thread(() -> {
                try {
                    downloadAndExtractModel(context, pref);
                } catch (Exception e) {
                    Log.e(TAG, "Download failed", e);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(context, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }

        private void downloadAndExtractModel(@NonNull Context context, ListPreference pref) throws Exception {
            Handler handler = new Handler(Looper.getMainLooper());
            File cacheDir = context.getCacheDir();
            File downloadFile = new File(cacheDir, "sensevoice.tar.bz2");

            // Download file
            URL url = new URL(ASR_MODEL_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.connect();

            int fileLength = connection.getContentLength();
            InputStream input = new BufferedInputStream(connection.getInputStream());
            FileOutputStream output = new FileOutputStream(downloadFile);

            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                output.write(buffer, 0, count);

                final long totalBytes = total;
                final int progress = fileLength > 0 ? (int) (total * 100 / fileLength) : 0;
                handler.post(() -> {
                    progressDialog.setProgress(progress);
                    progressDialog.setMessage("Downloading: " + (totalBytes / 1024 / 1024) + "MB");
                });
            }

            output.close();
            input.close();
            connection.disconnect();

            // Extract
            handler.post(() -> {
                progressDialog.setMessage("Extracting model...");
                progressDialog.setIndeterminate(true);
            });

            File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
            modelDir.mkdirs();

            FileInputStream fis = new FileInputStream(downloadFile);
            BZip2CompressorInputStream bzis = new BZip2CompressorInputStream(fis);
            TarArchiveInputStream tis = new TarArchiveInputStream(bzis);

            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                // Extract file name from path like "sherpa-onnx-sensevoice/model.int8.onnx"
                int slashIndex = name.lastIndexOf('/');
                if (slashIndex >= 0) {
                    name = name.substring(slashIndex + 1);
                }

                File outFile = new File(modelDir, name);
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = tis.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.close();
            }

            tis.close();
            bzis.close();
            fis.close();

            // Clean up download file
            downloadFile.delete();

            handler.post(() -> {
                progressDialog.dismiss();
                Toast.makeText(context, "Voice model installed successfully!", Toast.LENGTH_SHORT).show();
                pref.setValue("sensevoice");
                updateAsrModelSummary(context, pref);
            });
        }

    }

}
