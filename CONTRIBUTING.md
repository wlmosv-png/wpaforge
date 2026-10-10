# 贡献指南

## 环境

### 设备侧（跑 `aapt2`）

- Android 设备，arm64
- `aapt2` 二进制 + 其依赖的 `.so`（需 `LD_LIBRARY_PATH`）
- root（用于跑构建脚本和测试安装）

### 桌面侧（跑 `javac` / `d8`）

- JDK 17 或更高
- `d8.jar`（Android build-tools 提供）
- `apksigner.jar`
- `android.jar`（API 35）
- Python 3（辅助脚本）

## 构建

```sh
# 1. 设备侧：编译资源 + 生成 base.apk + R.java
sh 1-aapt2.sh

# 2. 桌面侧：javac + d8 + 组装 + 签名
sh 2-build-dex.sh
```

产物：`build/out/wpaforge-signed.apk`

### 脚本做了什么

**`1-aapt2.sh`**

```sh
aapt2 compile --dir res -o flat/res.zip
aapt2 link -o apk/base.apk -I android.jar -A assets \
           --manifest AndroidManifest.xml --java gen \
           --version-code 5 --version-name 0.5.0 flat/res.zip
```

关键点：
- `-A assets` 把内置字典打进去
- `--java gen` 生成 `R.java`，**必须传回给 javac**

**`2-build-dex.sh`**

```sh
javac --release 17 -cp android.jar -d classes \
      $(find app/src/main/java -name "*.java") \
      $(find build/out/gen -name "*.java")     # ← R.java 在这里

java -cp d8.jar com.android.tools.r8.D8 \
     --release --min-api 26 --lib android.jar --output dex $(find classes -name "*.class")

# 组包：三个文件必须 STORED
zip -0 unsigned.apk classes.dex AndroidManifest.xml resources.arsc
zip -r unsigned.apk . -x classes.dex AndroidManifest.xml resources.arsc

apksigner sign --ks release.jks ...
```

## 测试

```sh
javac --release 17 -encoding UTF-8 -cp android.jar \
      -d build/classes $(find app/src/main/java -name "*.java") \
                      $(find build/out/gen -name "*.java")

java -cp build/classes io.github.wlmosv_png.wpaforge.SelfTest
java -cp build/classes io.github.wlmosv_png.wpaforge.RuleTest
java -cp build/classes io.github.wlmosv_png.wpaforge.E2ETest
java -cp build/classes io.github.wlmosv_png.wpaforge.SortTest
java -cp build/classes io.github.wlmosv_png.wpaforge.TargetDictTest
```

全部通过才算改动合格。

### 测试覆盖

| 套件 | 覆盖 | 依赖 Android |
|---|---|---|
| `SelfTest` | 加密算法（RFC 向量）、`NetRecord` 解析、字典生成器 | 否 |
| `RuleTest` | 规则字典解析、展开、边界 | 否 |
| `E2ETest` | 握手比对端到端（自造握手 → 字典找回） | 否 |
| `SortTest` | 智能排序的优先级与稳定性 | 否 |
| `TargetDictTest` | 目标派生的类别覆盖与长度合法性 | 否 |

**注意**：`TaskQueue` 无法在桌面侧测试——`android.jar` 里的 `org.json` 是 stub，运行时才由系统提供实现。

## 常见陷阱

这些是实际踩过的，写下来省得重复：

### 1. 三个文件必须 STORED

`classes.dex`、`AndroidManifest.xml`、`resources.arsc` 在 APK 里必须是**不压缩**的。否则 Android 拒绝安装。

```sh
zip -q -X -0 out.apk classes.dex AndroidManifest.xml resources.arsc
zip -q -X -r out.apk . -x classes.dex AndroidManifest.xml resources.arsc
```

### 2. `d8` 是 bash 脚本

`d8` 本身是个包装脚本，在桌面侧直接执行会 `Permission denied`。要用 jar：

```sh
java -cp d8.jar com.android.tools.r8.D8 ...
```

### 3. `R.java` 必须参与编译

`aapt2 link --java` 生成的 `R.java` 在别的目录。javac 的源码列表里漏掉它，一旦代码引用 `R.xxx` 就编译失败。

### 4. 新增资源后要重跑 aapt2

往 `res/` 里加了图片后，**必须重跑 `1-aapt2.sh`**，否则 `R.java` 里没有对应条目。

### 5. 符号链接与权限

`/sdcard` 是 FUSE 挂载：
- `chmod` / `chown` **不生效**
- 用 root 创建的文件，其它 UID 读不到（除非同组）
- 媒体扫描器能进 MediaStore 的文件，普通应用才可见

### 6. Android 12+ 前台服务限制

从后台启动前台服务会抛 `ForegroundServiceStartNotAllowedException`。必须 try/catch 并降级。

### 7. 批量补丁脚本要用断言

改代码用脚本做批量替换时，**每次都断言匹配数量**：

```python
assert s.count(old) == 1, 'expected 1, got %d' % s.count(old)
```

否则匹配失败会静默跳过，你以为改了其实没改。

## 代码风格

- 注释写**为什么**，不写**是什么**
- 中文注释，标识符英文
- 不用第三方库（唯一例外是 `org.json`，Android 自带）
- 加密实现必须附带测试向量

## 提交

```
<模块>: <一句话说明>

<可选：为什么这么改，踩了什么坑>
```

例：

```
audit: 连接前先 forget 目标配置

系统里存着旧凭据时 connect-network 会复用，导致传入任意密码都报成功。
实测确认：先 forget 再连，错误密码约 3 秒返回 not connected。
```
