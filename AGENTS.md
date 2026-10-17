Project Context & Architecture
Type: Android Automation Software.
Stack: Kotlin, Jetpack Compose.
Architecture: The app functions as a host loader. It dynamically builds and loads logic from a specific source directory. This app will be run at Root envrionment. For all UI, try to use the componenets under app\src\main\java\com\coc\suncode\utils\components\Components.kt. 

When writing UI, if the UI has a key in Schema.kt, then you should display Schema.GLOBAL_SETTINGS.first { it.key == key }.displayName, instead of displaying hardcoded text.

Critical Directory Rules
Dynamic Source Path: app/src/main/java/com/coc/suncode/jar
Behavior: Treat all files in this directory as a standalone module. They are packed into a JAR and loaded dynamically at runtime.

I/O & Networking Constraints
Required I/O Method: All file reading and writing that are not in private path must use the host's internal Root backend. Dynamic modules must call the host file/configuration and shell APIs.
Root Backend: Host-owned Root app_process workers communicate through private stdin/stdout pipes. Do not add a WebSocket file server, a listening port, or an external server.apk dependency.

When adding debug information, you should use ShowMessage, which can be imported from com.coc.suncode.core.util.basic.ShowMessage.

When modify the code, you should add appropriate comments. Write all comments in English, but for other content (e.g. debug information, variable names or display information) can be written in Chinese.

When importing a class/object/function or others, you should import the full package name, and only use the last name inside the code.
For example, instead of using com.coc.suncode.jar.code.colorschema.ColorSchema in the code, you should import it as import com.coc.suncode.jar.code.colorschema.ColorSchema. Then, you can use ColorSchema in the code as ColorSchema.

When the instructions are unclear, you need to ask the user for clarification. Only proceed after you have understood every detail of the instructions.
