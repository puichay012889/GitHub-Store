package zed.rainxch.core.data.services.shizuku;

interface IShizukuInstallerService {
    int installPackage(String apkPath);
    int uninstallPackage(String packageName);
    void destroy();
}
