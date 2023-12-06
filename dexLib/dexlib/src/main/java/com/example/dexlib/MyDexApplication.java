package com.example.dexlib;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 用于替换原来的App入口
 */
public class MyDexApplication extends Application {

    /**
     * 原来的App名字
     */
    private String mSourceAppName;

    /**
     * 是否已经加载原来的App
     */
    private boolean mIsLoadSource;

    /**
     * 原来Application
     */
    private Application mSourceApplication;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            getSourceAppName();
            loadClassesDex();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getPackageName() {
        if (!TextUtils.isEmpty(mSourceAppName)) {
            return mSourceAppName;
        }
        return super.getPackageName();
    }

    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        if (TextUtils.isEmpty(mSourceAppName)) {
            return super.createPackageContext(packageName, flags);
        }
        try {
            loadApplication();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return mSourceApplication;
    }

    /**
     * 开始替换application
     */
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            loadApplication();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取原来Application的名称
     */
    private void getSourceAppName() throws Exception {
        String metaKey = "dexlib.application";
        ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(
                getPackageName(), PackageManager.GET_META_DATA);
        Bundle metaData = applicationInfo.metaData;
        if (null != metaData) {
            if (metaData.containsKey(metaKey)) {
                mSourceAppName = metaData.getString(metaKey);
            }
        }
    }

    /**
     * 解密Classes.dex文件
     * <p>
     * 这里只是简单的异或加密，需要自己修改
     */
    private byte[] decrypt(byte[] data, int key) {
        byte[] decrypted = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            decrypted[i] = (byte) (data[i] ^ key);
        }
        return decrypted;
    }

    /**
     * 加载原来的Classes.dex
     */
    private void loadClassesDex() throws Exception {

        List<File> dexFiles = new ArrayList<>();

        // 读取加密的DEX文件
        InputStream is = getAssets().open("dex.encypted");
        byte[] encryptedDex = new byte[is.available()];
        is.read(encryptedDex);
        is.close();

        // 解密DEX文件
        byte[] decryptedDex = decrypt(encryptedDex, 0x55);

        // 保存解密后的DEX文件
        File dexOutputDir = getDir("dex", Context.MODE_PRIVATE);
        File dexFile = new File(dexOutputDir, "classes.dex");
        FileOutputStream fos = new FileOutputStream(dexFile);
        fos.write(decryptedDex);
        fos.close();

        // TODO 这里只处理一个classes.dex情况，理论支持多个
        dexFiles.add(dexFile);

        // 获取pathList
        Field pathListField = null;
        Class clazz = getClassLoader().getClass();
        while (clazz != null) {
            try {
                pathListField = clazz.getDeclaredField("pathList");
                pathListField.setAccessible(true);
                break;
            } catch (NoSuchFieldException e) {
                //如果找不到往父类找
                clazz = clazz.getSuperclass();
            }
        }
        Object pathList = pathListField.get(getClassLoader());

        // 获取dexElements
        Field dexElementsField = null;
        clazz = pathList.getClass();
        while (clazz != null) {
            try {
                dexElementsField = clazz.getDeclaredField("dexElements");
                dexElementsField.setAccessible(true);
                break;
            } catch (NoSuchFieldException e) {
                //如果找不到往父类找
                clazz = clazz.getSuperclass();
            }
        }
        Object[] dexElements = (Object[]) dexElementsField.get(pathList);

        // 处理makePathElements
        Method makeDexElements = null;
        clazz = pathList.getClass();
        while (clazz != null) {
            try {
                makeDexElements = clazz.getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                makeDexElements.setAccessible(true);
                break;
            } catch (NoSuchMethodException e) {
                //如果找不到往父类找
                clazz = clazz.getSuperclass();
            }
        }

        ArrayList<IOException> suppressedException = new ArrayList<>();
        Object[] addElements = (Object[]) makeDexElements.invoke(pathList, dexFiles, null, suppressedException);

        Object[] newElements = (Object[]) Array.newInstance(dexElements.getClass().getComponentType(), dexElements.length + addElements.length);
        System.arraycopy(dexElements, 0, newElements, 0, dexElements.length);
        System.arraycopy(addElements, 0, newElements, dexElements.length, addElements.length);

        // 替换classloader中的element
        dexElementsField.set(pathList, newElements);
    }

    /**
     * 加载原来的Application
     */
    private void loadApplication() throws Exception {
        if (mIsLoadSource || TextUtils.isEmpty(mSourceAppName)) {
            return;
        }

        // 创建原来的Application
        mSourceApplication = (Application) Class.forName(mSourceAppName).newInstance();

        Context baseContext = getBaseContext();

        // 调用Attach
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(mSourceApplication, baseContext);

        // 设置mOuterContext
        Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
        Field mOuterContextField = contextImplClass.getDeclaredField("mOuterContext");
        mOuterContextField.setAccessible(true);
        mOuterContextField.set(baseContext, mSourceApplication);

        // 获取主线程
        Field mMainThreadField = contextImplClass.getDeclaredField("mMainThread");
        mMainThreadField.setAccessible(true);
        Object mMainThread = mMainThreadField.get(baseContext);

        // 修改mInitialApplication
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
        mInitialApplicationField.setAccessible(true);
        mInitialApplicationField.set(mMainThread, mSourceApplication);

        // 替换mAllApplications中的application
        Field mAllApplicationsField = activityThreadClass.getDeclaredField("mAllApplications");
        mAllApplicationsField.setAccessible(true);
        ArrayList<Application> mApplications = (ArrayList<Application>) mAllApplicationsField.get(mMainThread);
        mApplications.remove(this);
        mApplications.add(mSourceApplication);

        // 获取mPackageInfo
        Field mPackageInfoField = contextImplClass.getDeclaredField("mPackageInfo");
        mPackageInfoField.setAccessible(true);
        Object mPackageInfo = mPackageInfoField.get(baseContext);

        // 修改mApplication值
        Class<?> loadedApkClass = Class.forName("android.app.LoadedApk");
        Field mApplicationField = loadedApkClass.getDeclaredField("mApplication");
        mApplicationField.setAccessible(true);
        mApplicationField.set(mPackageInfo, mSourceApplication);

        // 修改ApplicationInfo
        Field mApplicationInfoField = loadedApkClass.getDeclaredField("mApplicationInfo");
        mApplicationInfoField.setAccessible(true);
        ApplicationInfo mApplicationInfo = (ApplicationInfo) mApplicationInfoField.get(mPackageInfo);
        mApplicationInfo.className = mSourceAppName;

        // 调用原来App的onCreate方法
        mSourceApplication.onCreate();
        mIsLoadSource = true;
    }

}
