import os
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as xmlET


# 解包APK
def unpack_apk(apk_full_path, output_folder):
    subprocess.run(["java", "-jar", apktool_path, "d", "-s", apk_full_path, "-o", output_folder],
                   check=True)


# 重新打包APK
def repack_apk(input_folder, output_apk):
    subprocess.run(["java", "-jar", apktool_path, "b", input_folder, "-o", output_apk], check=True)


# 对齐APK
def align_apk(unaligned_apk, aligned_apk):
    subprocess.run([zipalign_path, "-v", "4", unaligned_apk, aligned_apk], check=True)


# 签名APK V1
def sign_apk(keystore, storepass, keyalias, unsigned_apk, signed_apk):
    subprocess.run([
        "jarsigner", "-verbose", "-sigalg", "SHA1withRSA", "-digestalg", "SHA1",
        "-keystore", keystore,
        "-storepass", storepass,
        "-keypass", storepass,
        "-signedjar", signed_apk,
        unsigned_apk, keyalias
    ], check=True)


# 签名APK V2
def sign_apk_v2(keystore, storepass, keyalias, unsigned_apk, signed_apk):
    subprocess.run([
        "java", "-jar", apksigner_path, "sign",
        "--ks", keystore,
        "--ks-pass", f"pass:{storepass}",
        "--key-pass", f"pass:{storepass}",
        "--ks-key-alias", keyalias,
        "--out", signed_apk,
        unsigned_apk
    ], check=True)


# 清理工作环境
def clean_workspace():
    try:
        shutil.rmtree(unpack_folder)
    except Exception as ex:
        print(ex)
    remove_silent(repack_apk_path)
    remove_silent(aligned_apk_path)
    remove_silent(signed_apk_path_v1)
    remove_silent(signed_apk_path_v2)


# 悄悄删除文件
def remove_silent(path):
    try:
        os.remove(path)
    except Exception as ex:
        print(ex)


# 编译DexLib
def build_dex_lib_aar(libpath):
    subprocess.run(["gradlew.bat", "assembleRelease"], check=True, cwd=libpath, shell=True)
    # 解压aar文件
    aar_lib_path = os.path.join(libpath, "dexLib", "build", "outputs", "aar")
    extract_path = os.path.join(aar_lib_path, "extract")
    with zipfile.ZipFile(os.path.join(aar_lib_path, "dexlib-release.aar"),
                         'r') as zip_ref:
        zip_ref.extractall(extract_path)
    # classes.jar 转成 classes.dex
    subprocess.run(
        [d8_path, '--output', extract_path, os.path.join(extract_path, "classes.jar")],
        check=True,
        shell=True)
    return os.path.join(aar_lib_path, "extract", "classes.dex")


# 修改清单信息
def modify_manifest():
    xml_path = os.path.join(unpack_folder, 'AndroidManifest.xml')
    # 加载AndroidManifest.xml
    tree = xmlET.parse(xml_path)
    root = tree.getroot()

    # 定义命名空间（如果有的话）
    namespaces = {'android': 'http://schemas.android.com/apk/res/android'}
    for prefix, uri in namespaces.items():
        xmlET.register_namespace(prefix, uri)

    # 查找application标签
    application = root.find('application')

    # 获取原来的name属性值
    original_name = application.get('{%s}name' % namespaces['android'])

    # 设置新的name属性值
    application.set('{%s}name' % namespaces['android'], 'com.example.dexlib.MyDexApplication')
    application.set('{%s}debuggable' % namespaces['android'], 'true')

    # 创建meta-data标签
    meta_data = xmlET.Element('meta-data')

    # 设置meta-data的name和value属性
    meta_data.set('{%s}name' % namespaces['android'], 'dexlib.application')
    meta_data.set('{%s}value' % namespaces['android'], original_name if original_name is not None else '')

    # 将meta-data标签添加到application标签中
    application.append(meta_data)

    # 保存修改后的XML到新文件
    tree.write(xml_path, encoding='utf-8', xml_declaration=True)


# 替换Dex文件
def dex_replace():
    unpack_dex = os.path.join(unpack_folder, "classes.dex")
    unpack_assets = os.path.join(unpack_folder, "assets")
    os.mkdir(unpack_assets)
    # 加密
    # 此处只做了简单的异或加密文件，实际按需求来
    xor_file(unpack_dex, os.path.join(unpack_assets, "dex.encypted"))
    os.remove(unpack_dex)
    shutil.move(dex_path, unpack_dex)


# 异或加密文件
def xor_file(input_file_path, output_file_path):
    with open(input_file_path, 'rb') as input_file:
        data = input_file.read()

    key = 0x55  # 密钥，可以是0-255之间的任意数字
    xor_data = bytearray([b ^ key for b in data])

    with open(output_file_path, 'wb') as output_file:
        output_file.write(xor_data)


# 示例流程
if __name__ == "__main__":
    # 获取脚本文件的绝对路径
    script_path = os.path.abspath(sys.argv[0])
    # 获取脚本所在的目录
    script_dir = os.path.dirname(script_path)
    # 改变当前工作目录到脚本所在目录
    os.chdir(script_dir)

    # 下面是需要配置的环境信息
    apktool_path = "./thirdpart/apktool.jar"
    zipalign_path = "./thirdpart/zipalign"
    apksigner_path = "./thirdpart/apksigner.jar"
    d8_path = "./thirdpart/d8.bat"

    apk_path = "demo-app-release.apk"
    unpack_folder = "example_unpack"
    repack_apk_path = "example_repack.apk"
    signed_apk_path_v1 = "example_signed_v1.apk"
    signed_apk_path_v2 = "demo-app-output.apk"
    aligned_apk_path = "example_aligned.apk"
    key_store = "TestKeyStore.jks"
    key_store_pass = "12345678"
    alias_name = "12345678"

    # 清空工作环境
    clean_workspace()

    # 解包
    unpack_apk(apk_path, unpack_folder)

    # 编译Dex库
    dex_path = build_dex_lib_aar(os.path.join(script_dir, "dexLib"))

    # 替换Dex文件
    dex_replace()

    # 修改Manifest
    modify_manifest()

    # 重新打包
    repack_apk(unpack_folder, repack_apk_path)

    # 签名APK
    sign_apk(key_store, key_store_pass, alias_name, repack_apk_path, signed_apk_path_v1)

    # 对齐APK
    align_apk(signed_apk_path_v1, aligned_apk_path)

    # 签名APK V2
    sign_apk_v2(key_store, key_store_pass, alias_name, aligned_apk_path, signed_apk_path_v2)

    # 删除多余apk
    remove_silent(repack_apk_path)
    remove_silent(aligned_apk_path)
    remove_silent(signed_apk_path_v1)
