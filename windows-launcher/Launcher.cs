using System;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Drawing;
using System.Diagnostics;
using System.Collections.Generic;

internal static class Launcher {
    const string Endpoint = "https://referred-extras-unexpected-animal.trycloudflare.com";
    static string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NewscapeLauncher");
    static string endpoint = Endpoint;
    static bool installOnly, cancelled;
    static Label status;
    static ProgressBar progress;
    static Form window;
    static int downloaded, reused;
    [STAThread]
    static int Main(string[] args) {
        try {
            for (int i = 0; i < args.Length; i++) {
                if (args[i] == "--install-only") installOnly = true;
                else if (args[i] == "--root" && i + 1 < args.Length) root = Path.GetFullPath(args[++i]);
                else if (args[i] == "--endpoint" && i + 1 < args.Length) endpoint = args[++i].TrimEnd('/');
                else throw new ArgumentException("Unknown launcher option");
            }
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            Directory.CreateDirectory(root);
            if (installOnly) { Run(); return 0; }
            Application.EnableVisualStyles();
            window = new Form { Text = "Newscape", ClientSize = new Size(470, 175), StartPosition = FormStartPosition.CenterScreen, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false };
            var title = new Label { Text = "Newscape", Font = new Font("Segoe UI", 21), Location = new Point(22, 14), AutoSize = true };
            status = new Label { Text = "Preparing your client…", Location = new Point(24, 65), Size = new Size(420, 36) };
            progress = new ProgressBar { Location = new Point(24, 105), Size = new Size(420, 20) };
            var cancel = new Button { Text = "Cancel", Location = new Point(354, 137), Width = 90 };
            cancel.Click += delegate { cancelled = true; cancel.Enabled = false; status.Text = "Cancelling download…"; };
            window.FormClosing += delegate { cancelled = true; };
            window.Controls.AddRange(new Control[] { title, status, progress, cancel });
            window.Shown += delegate {
                var worker = new Thread(delegate() {
                    try { Run(); Finish(null); }
                    catch (Exception e) { Finish(e); }
                });
                worker.IsBackground = true;
                worker.Start();
            };
            Application.Run(window);
            return 0;
        } catch (Exception e) {
            try { File.WriteAllText(Path.Combine(root, "launcher-error.txt"), e.ToString()); } catch {}
            if (!installOnly) MessageBox.Show(e.Message, "Newscape");
            return 1;
        }
    }
    static void Finish(Exception error) {
        if (window.IsDisposed) return;
        window.BeginInvoke((Action)delegate {
            if (error != null && !cancelled) {
                File.WriteAllText(Path.Combine(root, "launcher-error.txt"), error.ToString());
                MessageBox.Show(window, "Could not prepare Newscape. Run the launcher again to retry.\n\n" + error.Message, "Newscape");
            }
            window.Close();
        });
    }
    static void Update(string text, int percent) {
        if (cancelled) throw new OperationCanceledException();
        if (window == null || window.IsDisposed) return;
        window.BeginInvoke((Action)delegate { status.Text = text; progress.Value = Math.Max(0, Math.Min(100, percent)); });
    }
    static string Hash(string path) {
        using (var sha = SHA256.Create())
        using (var stream = File.OpenRead(path))
            return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
    }
    static HttpWebResponse Open(string suffix) {
        var request = (HttpWebRequest)WebRequest.Create(endpoint + suffix);
        request.Timeout = 20000;
        request.ReadWriteTimeout = 20000;
        request.UserAgent = "NewscapeLauncher/1";
        return (HttpWebResponse)request.GetResponse();
    }
    static void Run() {
        // Keep the installation immutable for the lifetime of its game process.
        using (var gate = new FileStream(Path.Combine(root, "launcher.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
            Update("Checking client files…", 0);
            string manifest;
            using (var response = Open("/manifest.txt"))
            using (var stream = response.GetResponseStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8)) {
                var data = new char[1024 * 1024];
                int count = 0, n;
                while ((n = reader.Read(data, count, data.Length - count)) > 0) {
                    count += n;
                    if (count == data.Length) throw new IOException("Client manifest exceeds limit");
                }
                manifest = new string(data, 0, count);
            }
            var records = new List<string[]>();
            var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string install = Path.Combine(root, "client");
            Directory.CreateDirectory(install);
            long total = 0;
            foreach (string line in manifest.Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries)) {
                string[] fields = line.TrimEnd('\r').Split('|');
                long size;
                if (fields.Length != 3 || !long.TryParse(fields[0], out size) || size < 0 || size > 2147483648L ||
                    !System.Text.RegularExpressions.Regex.IsMatch(fields[1], "^[a-f0-9]{64}$"))
                    throw new IOException("Invalid client manifest");
                string relative = fields[2].Replace('/', Path.DirectorySeparatorChar);
                if (Path.IsPathRooted(relative) || relative.Contains(":") || !names.Add(relative)) throw new IOException("Invalid file path");
                string target = Path.GetFullPath(Path.Combine(install, relative));
                if (!target.StartsWith(install + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException("Invalid file path");
                total = checked(total + size);
                records.Add(new[] { fields[0], fields[1], target, fields[2] });
            }
            if (records.Count == 0 || total > 4L * 1024 * 1024 * 1024) throw new IOException("Invalid client size");
            long done = 0;
            foreach (var f in records) {
                long size = long.Parse(f[0]);
                Update("Checking " + f[3], (int)(done * 100 / Math.Max(1, total)));
                if (File.Exists(f[2]) && new FileInfo(f[2]).Length == size && Hash(f[2]) == f[1]) { reused++; done += size; continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(f[2]));
                string part = f[2] + ".download";
                try {
                    using (var response = Open("/files/" + f[1] + ".gz"))
                    using (var wire = response.GetResponseStream())
                    using (var gzip = new GZipStream(wire, CompressionMode.Decompress))
                    using (var output = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None)) {
                        byte[] buffer = new byte[65536];
                        long received = 0; int n;
                        while ((n = gzip.Read(buffer, 0, buffer.Length)) > 0) {
                            received += n;
                            if (received > size) throw new IOException("Downloaded file exceeds expected size");
                            output.Write(buffer, 0, n);
                            Update("Downloading client • " + ((done + received) / 1048576) + " / " + (total / 1048576) + " MB",
                                   (int)((done + received) * 100 / Math.Max(1, total)));
                        }
                        if (received != size) throw new IOException("Download ended early");
                    }
                    if (Hash(part) != f[1]) throw new IOException("Download verification failed");
                    if (File.Exists(f[2])) File.Replace(part, f[2], null);
                    else File.Move(part, f[2]);
                    downloaded++;
                } finally { if (File.Exists(part)) File.Delete(part); }
                done += size;
            }
            File.WriteAllText(Path.Combine(root, "last-update.txt"), "Downloaded " + downloaded + "\nReused " + reused + "\n");
            Update("Starting Newscape…", 100);
            if (installOnly) return;
            string profile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kronos-Windows");
            Directory.CreateDirectory(profile);
            var start = new ProcessStartInfo(Path.Combine(install, "runtime/bin/javaw.exe")) {
                WorkingDirectory = install,
                Arguments = "-Xmx1g " + (char)34 + "-Duser.home=" + profile + (char)34 + " -Dsun.java2d.d3d=false -cp " + (char)34 + "app/newscape-launcher.jar;app/windows-support.jar;app/client.jar" + (char)34 + " WindowsFreshCacheLauncher",
                UseShellExecute = false
            };
            using (var game = Process.Start(start)) {
                window.BeginInvoke((Action)delegate { window.Hide(); });
                game.WaitForExit();
            }
        }
    }
}


