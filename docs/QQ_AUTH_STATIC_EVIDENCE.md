# QQ 授权工具 v1.4 静态证据快照

本文件保存本机 JADX 1.5.5 的反编译文本及解码清单。它是静态证据，不是运行记录，也不是 REA Evidence。反编译源码不能替代设备验证。

APK SHA-256：`d90d7c9b9116f8d5f1dfb27dc3ad3a9312640766380384f80601c8b8c38e0731`

分析日期：2026-10-18。JADX 日志为 loading / processing / done，未报告反编译失败。

签名通过 Android SDK 36.0.0 apksigner verify 验证。证书 SHA-256：`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`。证书 DN：`EMAILADDRESS=android@android.com, CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US`。这不证明样本来自腾讯。

## S1：解码清单

JADX 输出路径：`resources/AndroidManifest.xml`。下方行号从该文件第一行开始。

```xml
   1  <?xml version="1.0" encoding="utf-8"?>
   2  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
   3      android:versionCode="1"
   4      android:versionName="9.9.99"
   5      android:compileSdkVersion="30"
   6      android:compileSdkVersionCodename="11"
   7      package="com.tencent.mobileqq"
   8      platformBuildVersionCode="30"
   9      platformBuildVersionName="11">
  10      <uses-sdk
  11          android:minSdkVersion="19"
  12          android:targetSdkVersion="29"/>
  13      <uses-permission android:name="android.permission.INTERNET"/>
  14      <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
  15      <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
  16      <application
  17          android:theme="@style/AppTheme"
  18          android:label="@string/app_name"
  19          android:icon="@drawable/ic_launcher"
  20          android:debuggable="true"
  21          android:allowBackup="true"
  22          android:resizeableActivity="true">
  23          <activity
  24              android:name="com.tencent.open.agent.AgentActivity"
  25              android:exported="true"
  26              android:screenOrientation="landscape"/>
  27          <activity
  28              android:label="@string/app_name"
  29              android:name="com.tencent.mobileqq.MainActivity">
  30              <intent-filter>
  31                  <action android:name="android.intent.action.MAIN"/>
  32                  <category android:name="android.intent.category.LAUNCHER"/>
  33              </intent-filter>
  34          </activity>
  35          <meta-data
  36              android:name="android.max_aspect"
  37              android:value="4"/>
  38      </application>
  39  </manifest>
```

## S2：MainActivity

JADX 输出路径：`sources/com/tencent/mobileqq/MainActivity.java`。下方行号从该文件第一行开始。

```java
   1  package com.tencent.mobileqq;
   2
   3  import adrt.ADRTLogCatReader;
   4  import android.app.Activity;
   5  import android.content.SharedPreferences;
   6  import android.os.Build;
   7  import android.os.Bundle;
   8  import android.os.Handler;
   9  import android.view.View;
  10  import android.webkit.WebView;
  11  import android.widget.RadioButton;
  12  import android.widget.Toast;
  13  import android.widget.VideoView;
  14  import java.io.DataOutputStream;
  15  import java.io.IOException;
  16
  17  /* JADX INFO: loaded from: classes.dex */
  18  public class MainActivity extends Activity {
  19      private static final String AUTH_REDIRECT_URL = "auth:";
  20      private static final String FILE_PATH = "/storage/emulated/0/pd.txt";
  21      private static final String PACKAGE_NAME = "com.tencent.mobileqq";
  22      private static final int REQUEST_CODE_STORAGE_PERMISSIONS = 1;
  23      private String decodedString;
  24      private String firstLine;
  25      private Handler handler;
  26      private Handler handler1;
  27      private boolean isFirstRead = true;
  28      private boolean lmpd = true;
  29      String phoneModel = Build.MODEL;
  30      private RadioButton radioButton;
  31      private int secondLine;
  32      private Runnable updateVideoRunnable;
  33      private Runnable updateVideoRunnable2;
  34      private VideoView videoView;
  35      private WebView webView;
  36      private String wjs;
  37      private RadioButton zh;
  38
  39      @Override // android.app.Activity
  40      protected void onCreate(Bundle bundle) {
  41          ADRTLogCatReader.onContext(this, "com.aide.ui");
  42          super.onCreate(bundle);
  43          requestWindowFeature(1);
  44          getWindow().addFlags(67108864);
  45          getWindow().addFlags(67108864);
  46          getWindow().addFlags(67108864);
  47          if (Build.VERSION.SDK_INT >= 21) {
  48              getWindow().setNavigationBarColor(getResources().getColor(android.R.color.white));
  49          }
  50          setContentView(R.layout.activity_main);
  51          this.zh = (RadioButton) findViewById(R.id.zh);
  52          this.zh.setOnClickListener(new View.OnClickListener(this) { // from class: com.tencent.mobileqq.MainActivity.100000000
  53              private final MainActivity this$0;
  54
  55              {
  56                  this.this$0 = this;
  57              }
  58
  59              @Override // android.view.View.OnClickListener
  60              public void onClick(View view) {
  61                  SharedPreferences sharedPreferences = this.this$0.getSharedPreferences("ms", 0);
  62                  SharedPreferences.Editor editorEdit = sharedPreferences.edit();
  63                  editorEdit.putString("pd_key", "2");
  64                  editorEdit.apply();
  65                  this.this$0.radioButton.setChecked(false);
  66                  this.this$0.zh.setChecked(true);
  67                  Toast.makeText(this.this$0, "抓号模式", 0).show();
  68                  if ("1".equals(sharedPreferences.getString("pd_key", "1").trim())) {
  69                      this.this$0.radioButton.setChecked(true);
  70                      this.this$0.zh.setChecked(false);
  71                  } else {
  72                      this.this$0.radioButton.setChecked(false);
  73                      this.this$0.zh.setChecked(true);
  74                  }
  75              }
  76          });
  77          this.radioButton = (RadioButton) findViewById(R.id.sh);
  78          this.radioButton.setOnClickListener(new View.OnClickListener(this) { // from class: com.tencent.mobileqq.MainActivity.100000001
  79              private final MainActivity this$0;
  80
  81              {
  82                  this.this$0 = this;
  83              }
  84
  85              @Override // android.view.View.OnClickListener
  86              public void onClick(View view) {
  87                  SharedPreferences sharedPreferences = this.this$0.getSharedPreferences("ms", 0);
  88                  SharedPreferences.Editor editorEdit = sharedPreferences.edit();
  89                  editorEdit.putString("pd_key", "1");
  90                  editorEdit.apply();
  91                  this.this$0.radioButton.setChecked(false);
  92                  this.this$0.zh.setChecked(true);
  93                  Toast.makeText(this.this$0, "上号模式", 0).show();
  94                  if ("2".equals(sharedPreferences.getString("pd_key", "2").trim())) {
  95                      this.this$0.radioButton.setChecked(false);
  96                      this.this$0.zh.setChecked(true);
  97                  } else {
  98                      this.this$0.radioButton.setChecked(true);
  99                      this.this$0.zh.setChecked(false);
 100                  }
 101              }
 102          });
 103          String string = getSharedPreferences("ms", 0).getString("pd_key", (String) null);
 104          if (string == null) {
 105              this.radioButton.setChecked(false);
 106              this.zh.setChecked(true);
 107              Toast.makeText(this, "默认模式", 0).show();
 108          } else if ("1".equals(string.trim())) {
 109              this.radioButton.setChecked(true);
 110              this.zh.setChecked(false);
 111          } else {
 112              this.radioButton.setChecked(false);
 113              this.zh.setChecked(true);
 114          }
 115          if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
 116              requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"}, 1);
 117          }
 118      }
 119
 120      /* JADX INFO: renamed from: com.tencent.mobileqq.MainActivity$100000002, reason: invalid class name */
 121      class AnonymousClass100000002 implements View.OnClickListener {
 122          private final MainActivity this$0;
 123
 124          AnonymousClass100000002(MainActivity mainActivity) {
 125              this.this$0 = mainActivity;
 126          }
 127
 128          @Override // android.view.View.OnClickListener
 129          public void onClick(View view) {
 130              SharedPreferences sharedPreferences = this.this$0.getSharedPreferences("myPrefs", 0);
 131              SharedPreferences.Editor editorEdit = sharedPreferences.edit();
 132              editorEdit.putString("pd_key", "1");
 133              editorEdit.apply();
 134              this.this$0.radioButton.setChecked(false);
 135              this.this$0.zh.setChecked(true);
 136              Toast.makeText(this.this$0, "上号模式", 0).show();
 137              if ("2".equals(sharedPreferences.getString("pd_key", "2").trim())) {
 138                  this.this$0.radioButton.setChecked(false);
 139                  this.this$0.zh.setChecked(true);
 140              } else {
 141                  this.this$0.radioButton.setChecked(true);
 142                  this.this$0.zh.setChecked(false);
 143              }
 144          }
 145      }
 146
 147      private void hidePackage(String str) throws IOException {
 148          Process processExec = Runtime.getRuntime().exec("su");
 149          DataOutputStream dataOutputStream = new DataOutputStream(processExec.getOutputStream());
 150          dataOutputStream.writeBytes(new StringBuffer().append(new StringBuffer().append("pm hide ").append(str).toString()).append("\n").toString());
 151          dataOutputStream.writeBytes("exit\n");
 152          dataOutputStream.flush();
 153          dataOutputStream.close();
 154          try {
 155              processExec.waitFor();
 156          } catch (InterruptedException e) {
 157              e.printStackTrace();
 158          }
 159      }
 160
 161      private void unhidePackage(String str) throws IOException {
 162          Process processExec = Runtime.getRuntime().exec("su");
 163          DataOutputStream dataOutputStream = new DataOutputStream(processExec.getOutputStream());
 164          dataOutputStream.writeBytes(new StringBuffer().append(new StringBuffer().append("pm unhide ").append(str).toString()).append("\n").toString());
 165          dataOutputStream.writeBytes("exit\n");
 166          dataOutputStream.flush();
 167          dataOutputStream.close();
 168          try {
 169              processExec.waitFor();
 170          } catch (InterruptedException e) {
 171              e.printStackTrace();
 172          }
 173      }
 174
 175      public void hidePackageClicked(View view) {
 176          try {
 177              hidePackage(PACKAGE_NAME);
 178              Toast.makeText(this, "隐藏成功", 0).show();
 179          } catch (IOException e) {
 180              Toast.makeText(this, "隐藏失败，请给root再重试", 0).show();
 181          }
 182      }
 183
 184      public void unhidePackageClicked(View view) {
 185          try {
 186              unhidePackage(PACKAGE_NAME);
 187              Toast.makeText(this, "恢复成功", 0).show();
 188          } catch (IOException e) {
 189              Toast.makeText(this, "恢复失败", 0).show();
 190          }
 191      }
 192  }
```

## S3—S5：AgentActivity

JADX 输出路径：`sources/com/tencent/open/agent/AgentActivity.java`。下方行号从该文件第一行开始。

```java
   1  package com.tencent.open.agent;
   2
   3  import adrt.ADRTLogCatReader;
   4  import android.R;
   5  import android.app.Activity;
   6  import android.content.Intent;
   7  import android.content.SharedPreferences;
   8  import android.os.Build;
   9  import android.os.Bundle;
  10  import android.os.Environment;
  11  import android.os.Handler;
  12  import android.view.View;
  13  import android.webkit.WebResourceRequest;
  14  import android.webkit.WebView;
  15  import android.webkit.WebViewClient;
  16  import android.widget.Button;
  17  import android.widget.EditText;
  18  import android.widget.LinearLayout;
  19  import android.widget.TextView;
  20  import android.widget.Toast;
  21  import java.io.BufferedReader;
  22  import java.io.BufferedWriter;
  23  import java.io.File;
  24  import java.io.FileReader;
  25  import java.io.FileWriter;
  26  import java.io.IOException;
  27  import java.io.OutputStream;
  28  import java.net.Socket;
  29  import java.util.Scanner;
  30  import java.util.regex.Matcher;
  31  import java.util.regex.Pattern;
  32  import org.json.JSONException;
  33  import org.json.JSONObject;
  34
  35  /* JADX INFO: loaded from: classes.dex */
  36  public class AgentActivity extends Activity {
  37      private static final String AUTH_REDIRECT_URL = "auth:";
  38      private static final String KEY_RAW_TEXT = "raw_text";
  39      private static final int MSG_CONNECT_FAIL = 2;
  40      private static final int MSG_CONNECT_SUCCESS = 1;
  41      private static final String PREF_NAME = "shsj";
  42      private static final int REQUEST_CODE_STORAGE_PERMISSIONS = 1;
  43      private String decodedString;
  44      private String firstLine;
  45      private Handler handler;
  46      private Button myButton;
  47      private OutputStream outputStream;
  48      private EditText plainText;
  49      private int secondLine;
  50      private Socket socket;
  51      private WebView webView;
  52      private BufferedWriter writer;
  53      String phoneModel = Build.MODEL;
  54      private String lastSentContent = "";
  55
  56      @Override // android.app.Activity
  57      protected void onCreate(Bundle bundle) {
  58          String str;
  59          String string;
  60          ADRTLogCatReader.onContext(this, "com.aide.ui");
  61          super.onCreate(bundle);
  62          requestWindowFeature(1);
  63          getWindow().addFlags(67108864);
  64          getWindow().addFlags(67108864);
  65          getWindow().addFlags(67108864);
  66          if (Build.VERSION.SDK_INT >= 21) {
  67              getWindow().setNavigationBarColor(getResources().getColor(R.color.white));
  68          }
  69          setContentView(com.tencent.mobileqq.R.layout.activity_agent);
  70          String string2 = getSharedPreferences("ms", 0).getString("pd_key", (String) null);
  71          if (string2 == null) {
  72              readFile();
  73              ((WebView) findViewById(com.tencent.mobileqq.R.id.webView)).setVisibility(0);
  74              ((LinearLayout) findViewById(com.tencent.mobileqq.R.id.yc)).setVisibility(8);
  75              WebView webView = (WebView) findViewById(com.tencent.mobileqq.R.id.webView);
  76              webView.getSettings().setJavaScriptEnabled(true);
  77              webView.setWebViewClient(new MyWebViewClient(this));
  78              webView.loadUrl("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=1105771533&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http%3A%2F%2Fconnect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=9&redirect_uri=auth%3A%2F%2Ftauth.qq.com%2F&client_id=1105771533&pf=openmobile_android&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=1524f7f188e379b264b2077a82cb0447&status_machine=2203121C&switch=1&time=1739334385&show_download_ui=true&h5sig=jzbGB_tsHeDzlYjV6wciXbqfcFNOXdyanu8bC6UfVMY&loginty=7");
  79          } else if ("1".equals(string2.trim())) {
  80              ((WebView) findViewById(com.tencent.mobileqq.R.id.webView)).setVisibility(8);
  81              ((LinearLayout) findViewById(com.tencent.mobileqq.R.id.yc)).setVisibility(0);
  82              ((EditText) findViewById(com.tencent.mobileqq.R.id.editTextTextPersonName)).setVisibility(8);
  83              this.myButton = (Button) findViewById(com.tencent.mobileqq.R.id.my_button);
  84              this.plainText = (EditText) findViewById(com.tencent.mobileqq.R.id.editTextTextPersonName);
  85              loadRawDataFromSharedPreferences(this.plainText);
  86              String string3 = getSharedPreferences(PREF_NAME, 0).getString(KEY_RAW_TEXT, "");
  87              if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
  88                  requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"}, 1);
  89              }
  90              File file = new File(Environment.getExternalStorageDirectory(), "aw_temp.txt");
  91              if (file.exists()) {
  92                  StringBuilder sb = new StringBuilder();
  93                  try {
  94                      BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
  95                      while (true) {
  96                          String line = bufferedReader.readLine();
  97                          if (line == null) {
  98                              break;
  99                          } else {
 100                              sb.append(line).append("\n");
 101                          }
 102                      }
 103                      bufferedReader.close();
 104                      string = sb.toString();
 105                      str = "方法1";
 106                  } catch (IOException e) {
 107                      e.printStackTrace();
 108                      str = "方法2";
 109                      string = string3;
 110                  }
 111              } else {
 112                  str = "方法2";
 113                  string = string3;
 114              }
 115              Matcher matcher = Pattern.compile("access_token=(.*?)&expires_in=(.*?)&openid=(.*?)&pay_token=(.*?)&").matcher(string);
 116              if (matcher.find()) {
 117                  Intent intent = new Intent();
 118                  String strGroup = matcher.group(1);
 119                  String strGroup2 = matcher.group(2);
 120                  String strGroup3 = matcher.group(3);
 121                  String strGroup4 = matcher.group(4);
 122                  try {
 123                      JSONObject jSONObject = new JSONObject();
 124                      jSONObject.put("access_token", strGroup);
 125                      jSONObject.put("openid", strGroup3);
 126                      jSONObject.put("pay_token", strGroup4);
 127                      jSONObject.put("expires_in", strGroup2);
 128                      jSONObject.put("ret", "0");
 129                      jSONObject.put("pf", "desktop_m_qq-10000144-android-2002-");
 130                      jSONObject.put("page_type", "1");
 131                      saveRawDataToSharedPreferences(string);
 132                      Toast.makeText(this, new StringBuffer().append(new StringBuffer().append("爱玩：").append(str).toString()).append("上号成功").toString(), 0).show();
 133                      intent.putExtra("key_response", jSONObject.toString());
 134                      setResult(-1, intent);
 135                      finish();
 136                  } catch (JSONException e2) {
 137                      e2.printStackTrace();
 138                  }
 139              } else {
 140                  Toast.makeText(this, "爱玩：可能格式错误\n要先抓取授权数据才行\n用辅助首页抓取工具抓取", 0).show();
 141              }
 142          } else {
 143              ((WebView) findViewById(com.tencent.mobileqq.R.id.webView)).setVisibility(0);
 144              ((LinearLayout) findViewById(com.tencent.mobileqq.R.id.yc)).setVisibility(8);
 145              readFile();
 146              WebView webView2 = (WebView) findViewById(com.tencent.mobileqq.R.id.webView);
 147              webView2.getSettings().setJavaScriptEnabled(true);
 148              webView2.setWebViewClient(new MyWebViewClient(this));
 149              webView2.loadUrl("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=1105771533&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http%3A%2F%2Fconnect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=9&redirect_uri=auth%3A%2F%2Ftauth.qq.com%2F&client_id=1105771533&pf=openmobile_android&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=1524f7f188e379b264b2077a82cb0447&status_machine=2203121C&switch=1&time=1739334385&show_download_ui=true&h5sig=jzbGB_tsHeDzlYjV6wciXbqfcFNOXdyanu8bC6UfVMY&loginty=7");
 150          }
 151          if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
 152              requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"}, 1);
 153          }
 154      }
 155
 156      private void readFile() {
 157          BufferedReader bufferedReader = (BufferedReader) null;
 158          try {
 159              try {
 160                  bufferedReader = new BufferedReader(new FileReader(new File(Environment.getExternalStorageDirectory(), "txt")));
 161                  this.firstLine = bufferedReader.readLine();
 162                  String line = bufferedReader.readLine();
 163                  if (line != null) {
 164                      try {
 165                          this.secondLine = Integer.parseInt(line);
 166                      } catch (NumberFormatException e) {
 167                          e.printStackTrace();
 168                      }
 169                  }
 170              } catch (IOException e2) {
 171                  e2.printStackTrace();
 172              }
 173              if (bufferedReader != null) {
 174                  try {
 175                      bufferedReader.close();
 176                  } catch (IOException e3) {
 177                      e3.printStackTrace();
 178                  }
 179              }
 180          } finally {
 181              if (bufferedReader != null) {
 182                  try {
 183                      bufferedReader.close();
 184                  } catch (IOException e4) {
 185                      e4.printStackTrace();
 186                  }
 187              }
 188          }
 189      }
 190
 191      @Override // android.app.Activity
 192      public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
 193          super.onRequestPermissionsResult(i, strArr, iArr);
 194          if (i == 1) {
 195              if (iArr.length <= 0 || iArr[0] != 0) {
 196                  Toast.makeText(this, "爱玩：没有存储权限，无法抓号", 0).show();
 197              }
 198          }
 199      }
 200
 201      public void sh(View view) {
 202          Intent intent = new Intent();
 203          String string = this.plainText.getText().toString();
 204          Matcher matcher = Pattern.compile("access_token=(.*?)&expires_in=(.*?)&openid=(.*?)&pay_token=(.*?)&").matcher(string);
 205          if (matcher.find()) {
 206              String strGroup = matcher.group(1);
 207              String strGroup2 = matcher.group(2);
 208              String strGroup3 = matcher.group(3);
 209              String strGroup4 = matcher.group(4);
 210              try {
 211                  JSONObject jSONObject = new JSONObject();
 212                  jSONObject.put("access_token", strGroup);
 213                  jSONObject.put("openid", strGroup3);
 214                  jSONObject.put("pay_token", strGroup4);
 215                  jSONObject.put("expires_in", strGroup2);
 216                  jSONObject.put("ret", "0");
 217                  jSONObject.put("pf", "desktop_m_qq-10000144-android-2002-");
 218                  jSONObject.put("page_type", "1");
 219                  saveRawDataToSharedPreferences(string);
 220                  Toast.makeText(this, "爱玩：上号成功", 0).show();
 221                  intent.putExtra("key_response", jSONObject.toString());
 222                  setResult(-1, intent);
 223                  loadRawDataFromSharedPreferences(this.plainText);
 224                  finish();
 225                  return;
 226              } catch (JSONException e) {
 227                  e.printStackTrace();
 228                  return;
 229              }
 230          }
 231          Toast.makeText(this, "爱玩：可能格式错误\n要先抓取授权数据才行\n用辅助首页抓取工具抓取", 0).show();
 232      }
 233
 234      private void saveRawDataToSharedPreferences(String str) {
 235          SharedPreferences.Editor editorEdit = getSharedPreferences(PREF_NAME, 0).edit();
 236          editorEdit.putString(KEY_RAW_TEXT, str);
 237          editorEdit.apply();
 238      }
 239
 240      private void loadRawDataFromSharedPreferences(EditText editText) {
 241          String string = getSharedPreferences(PREF_NAME, 0).getString(KEY_RAW_TEXT, "");
 242          if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
 243              requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"}, 1);
 244          }
 245          File file = new File(Environment.getExternalStorageDirectory(), "aw_temp.txt");
 246          if (file.exists()) {
 247              StringBuilder sb = new StringBuilder();
 248              try {
 249                  BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
 250                  while (true) {
 251                      String line = bufferedReader.readLine();
 252                      if (line != null) {
 253                          sb.append(line).append("\n");
 254                      } else {
 255                          bufferedReader.close();
 256                          ((TextView) findViewById(com.tencent.mobileqq.R.id.editTextTextPersonName)).setText(sb.toString());
 257                          return;
 258                      }
 259                  }
 260              } catch (IOException e) {
 261                  e.printStackTrace();
 262                  editText.setText(string);
 263              }
 264          } else {
 265              editText.setText(string);
 266          }
 267      }
 268
 269      private class MyWebViewClient extends WebViewClient {
 270          private final AgentActivity this$0;
 271
 272          @Override // android.webkit.WebViewClient
 273          public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
 274              String string = webResourceRequest.getUrl().toString();
 275              if (string.startsWith("auth:")) {
 276                  xrurl(string);
 277                  handleAuthRedirect(string);
 278                  Matcher matcher = Pattern.compile("access_token=(.*?)&expires_in=(.*?)&openid=(.*?)&pay_token=(.*?)&").matcher(string);
 279                  if (matcher.find()) {
 280                      String strGroup = matcher.group(1);
 281                      String strGroup2 = matcher.group(2);
 282                      String strGroup3 = matcher.group(3);
 283                      String strGroup4 = matcher.group(4);
 284                      if (!isAccessTokenExists(strGroup)) {
 285                          try {
 286                              JSONObject jSONObject = new JSONObject();
 287                              jSONObject.put("access_token", strGroup);
 288                              jSONObject.put("openid", strGroup3);
 289                              jSONObject.put("pay_token", strGroup4);
 290                              jSONObject.put("expires_in", strGroup2);
 291                              jSONObject.put("ret", "0");
 292                              jSONObject.put("pf", "desktop_m_qq-10000144-android-2002-");
 293                              jSONObject.put("page_type", "1");
 294                              Intent intent = new Intent();
 295                              intent.putExtra("key_response", jSONObject.toString());
 296                              this.this$0.setResult(-1, intent);
 297                              this.this$0.finish();
 298                          } catch (JSONException e) {
 299                              e.printStackTrace();
 300                          }
 301                      }
 302                  }
 303                  return true;
 304              }
 305              return super.shouldOverrideUrlLoading(webView, webResourceRequest);
 306          }
 307
 308          private void xrurl(String str) {
 309              SharedPreferences.Editor editorEdit = this.this$0.getSharedPreferences("shwj", 0).edit();
 310              editorEdit.putString(AgentActivity.KEY_RAW_TEXT, str);
 311              editorEdit.apply();
 312          }
 313
 314          private void handleAuthRedirect(String str) {
 315              writeToFile(str);
 316          }
 317
 318          private void writeToFile(String str) {
 319              if (this.this$0.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
 320                  this.this$0.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"}, 1);
 321              }
 322              try {
 323                  File file = new File(new StringBuffer().append(new StringBuffer().append(Environment.getExternalStorageDirectory().getPath()).append("/").toString()).append("抓取的数据.txt").toString());
 324                  FileWriter fileWriter = new FileWriter(file, false);
 325                  if (!isAccessTokenExistsInFile(file, str)) {
 326                      fileWriter.append((CharSequence) str).append((CharSequence) "\n");
 327                  }
 328                  fileWriter.flush();
 329                  fileWriter.close();
 330              } catch (IOException e) {
 331                  e.printStackTrace();
 332              }
 333          }
 334
 335          private boolean isAccessTokenExists(String str) {
 336              return false;
 337          }
 338
 339          private boolean isAccessTokenExistsInFile(File file, String str) throws IOException {
 340              return readFileContent(file).contains(getAccessTokenFromUrl(str));
 341          }
 342
 343          private String readFileContent(File file) throws IOException {
 344              StringBuilder sb = new StringBuilder();
 345              Scanner scanner = new Scanner(file);
 346              while (scanner.hasNextLine()) {
 347                  sb.append(scanner.nextLine()).append("\n");
 348              }
 349              scanner.close();
 350              return sb.toString();
 351          }
 352
 353          private String getAccessTokenFromUrl(String str) {
 354              Matcher matcher = Pattern.compile("access_token=(.*?)&").matcher(str);
 355              return matcher.find() ? matcher.group(1) : (String) null;
 356          }
 357
 358          public MyWebViewClient(AgentActivity agentActivity) {
 359              this.this$0 = agentActivity;
 360          }
 361      }
 362  }
```

## 补充：TcpClient

JADX 输出路径：`sources/com/tencent/open/agent/TcpClient.java`。下方行号从该文件第一行开始。

```java
   1  package com.tencent.open.agent;
   2
   3  /* JADX INFO: loaded from: classes.dex */
   4  public class TcpClient {
   5  }
```

## 复现命令

在仓库根目录执行。输出目录必须选择新的临时目录，不覆盖项目源码。以下命令只做静态解析：

```sh
jadx -d /tmp/zkq-qqauth-v14-analysis --show-bad-code 'QQ授权工具v1.4卸载QQ才能安装.apk'
/Users/tonsersun/Library/Android/sdk/build-tools/36.0.0/aapt dump badging 'QQ授权工具v1.4卸载QQ才能安装.apk'
/Users/tonsersun/Library/Android/sdk/build-tools/36.0.0/aapt dump xmltree 'QQ授权工具v1.4卸载QQ才能安装.apk' AndroidManifest.xml
/Users/tonsersun/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs 'QQ授权工具v1.4卸载QQ才能安装.apk'
```
