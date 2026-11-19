using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Windows.Forms;
using System.Reflection;
using System.Threading.Tasks;

internal static class StandaloneLauncher
{
    private const string BundleResource = "NewscapeBundle";
    private static readonly string Local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
    private static string root = Path.Combine(Local, "NewscapeStandalone");
    private static string profile = Path.Combine(Local, "Kronos-Windows");
    private static bool installOnly;
    private static bool bootCheck;
    private static int exitCode;

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            for (int i = 0; i < args.Length; i++)
            {
                switch (args[i])
                {
                    case "--install-only": installOnly = true; break;
                    case "--check-login-screen": bootCheck = true; break;
                    case "--root": root = Path.GetFullPath(args[++i]); break;
                    case "--profile": profile = Path.GetFullPath(args[++i]); break;
                    default: throw new ArgumentException("Unknown launcher option.");
                }
            }
            if (installOnly || bootCheck)
            {
                using (var game = PrepareAndLaunch(null))
                {
                    if (installOnly)
                    {
                        if (!game.WaitForExit(120000)) throw new IOException("Cache verification timed out.");
                        return game.ExitCode;
                    }
                    if (game.WaitForExit(15000)) throw new IOException("The game closed during startup.");
                    File.WriteAllText(Path.Combine(root, "boot-check.pid"), game.Id.ToString());
                    return 0;
                }
            }
            Application.EnableVisualStyles();
            using (var window = new Form {
                Text = "Newscape", ClientSize = new Size(408, 118),
                FormBorderStyle = FormBorderStyle.FixedDialog, StartPosition = FormStartPosition.CenterScreen,
                ControlBox = false
            })
            {
                var status = new Label { Location = new Point(22, 22), Size = new Size(360, 30), Text = "Checking Newscape files…" };
                var bar = new ProgressBar { Location = new Point(22, 67), Size = new Size(364, 20) };
                window.Controls.Add(status);
                window.Controls.Add(bar);
                window.Shown += async delegate
                {
                    var reporter = new Progress<int>(p =>
                    {
                        if (window.IsDisposed) return;
                        bar.Value = p;
                        status.Text = p == 100 ? "Starting Newscape…" : "Preparing Newscape files…";
                    });
                    try { using (await Task.Run(() => PrepareAndLaunch(reporter))) { } }
                    catch (Exception error)
                    {
                        WriteError(error);
                        exitCode = 1;
                        MessageBox.Show(window, error.Message, "Newscape startup", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    }
                    finally { window.Close(); }
                };
                Application.Run(window);
                return exitCode;
            }
        }
        catch (Exception error)
        {
            WriteError(error);
            if (!installOnly && !bootCheck) MessageBox.Show(error.Message, "Newscape startup");
            return 1;
        }
    }

    private static Process PrepareAndLaunch(IProgress<int> progress)
    {
        Directory.CreateDirectory(root);
        RejectRedirect(root);
        Directory.CreateDirectory(profile);
        var lockPath = Path.Combine(root, "install.lock");
        if (File.Exists(lockPath)) RejectRedirect(lockPath);
        var install = Path.Combine(root, "expedition-v46-" + BundleMetadata.ArchiveHash.Substring(0, 12));
        using (var gate = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None))
        {
            if (!IsReady(install)) Extract(install, progress);
        }
        if (progress != null) progress.Report(100);
        string executable = installOnly ? "java.exe" : "javaw.exe";
        var start = new ProcessStartInfo(Path.Combine(install, "runtime", "bin", executable))
        {
            WorkingDirectory = install,
            Arguments = "-Xmx1g " + Quote("-Duser.home=" + profile) + " -Dsun.java2d.d3d=false"
                + (installOnly ? " -Djava.awt.headless=true" : "")
                + " -cp " + Quote("app/newscape-launcher.jar;app/windows-support.jar;app/client.jar")
                + " WindowsFreshCacheLauncher" + (installOnly ? " --install-only" : ""),
            UseShellExecute = false,
            CreateNoWindow = installOnly
        };
        var game = Process.Start(start);
        if (game == null) throw new IOException("Windows did not start the bundled Java runtime.");
        return game;
    }

    private static bool IsReady(string install)
    {
        if (!Directory.Exists(install)) return false;
        RejectRedirect(install);
        var marker = Path.Combine(install, ".verified-sha256");
        if (!File.Exists(marker)) return false;
        RejectRedirect(marker);
        return File.ReadAllText(marker) == BundleMetadata.ArchiveHash
            && Matches(install, "app/client.jar", BundleMetadata.ClientHash)
            && Matches(install, "app/newscape-launcher.jar", BundleMetadata.LauncherHash)
            && Matches(install, "app/windows-support.jar", BundleMetadata.SupportHash)
            && Matches(install, "runtime/bin/java.exe", BundleMetadata.JavaHash)
            && Matches(install, "runtime/bin/javaw.exe", BundleMetadata.JavaWindowHash);
    }

    private static bool Matches(string basePath, string relative, string expected)
    {
        var file = Path.Combine(basePath, relative);
        if (!File.Exists(file)) return false;
        RejectRedirect(Path.GetDirectoryName(file));
        RejectRedirect(file);
        using (var input = File.OpenRead(file)) return Digest(input) == expected;
    }

    private static void Extract(string install, IProgress<int> progress)
    {
        var stage = ChildPath(root, ".unpack-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stage);
        try
        {
            using (var payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(BundleResource))
            {
                if (payload == null || Digest(payload) != BundleMetadata.ArchiveHash)
                    throw new IOException("The embedded Newscape files failed verification.");
                payload.Position = 0;
                using (var archive = new ZipArchive(payload, ZipArchiveMode.Read))
                {
                    int done = 0;
                    foreach (var entry in archive.Entries)
                    {
                        var target = ChildPath(stage, entry.FullName);
                        Directory.CreateDirectory(Path.GetDirectoryName(target));
                        using (var source = entry.Open())
                        using (var destination = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                            source.CopyTo(destination, 131072);
                        if (progress != null) progress.Report(1 + (++done * 98 / archive.Entries.Count));
                    }
                }
            }
            File.WriteAllText(Path.Combine(stage, ".verified-sha256"), BundleMetadata.ArchiveHash);
            if (!IsReady(stage)) throw new IOException("The extracted client files failed verification.");
            if (Directory.Exists(install))
            {
                RejectRedirect(install);
                Directory.Move(install, Path.Combine(root, ".previous-" + Guid.NewGuid().ToString("N")));
            }
            Directory.Move(stage, install);
        }
        finally
        {
            if (Directory.Exists(stage))
            {
                RejectRedirect(stage);
                Directory.Delete(stage, true);
            }
        }
    }

    private static string ChildPath(string parent, string relative)
    {
        if (relative.Contains(":") || relative.Contains("\\") || relative.StartsWith("/") ||
            Array.Exists(relative.Split('/'), part => part == "." || part == ".." || part.Length == 0))
            throw new IOException("Invalid embedded file path.");
        var full = Path.GetFullPath(Path.Combine(parent, relative));
        if (!full.StartsWith(parent + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Embedded file path leaves the client folder.");
        return full;
    }

    private static void RejectRedirect(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Newscape files may not use redirected directories.");
    }

    private static string Digest(Stream stream)
    {
        using (var sha = SHA256.Create())
            return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
    }

    private static string Quote(string value)
    {
        return "\"" + value.TrimEnd('\\') + "\"";
    }

    private static void WriteError(Exception error)
    {
        try
        {
            Directory.CreateDirectory(root);
            File.WriteAllText(Path.Combine(root, "standalone-error.txt"), error.ToString());
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
