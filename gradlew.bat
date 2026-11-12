@rem ---------------------------------------------------------------------------
@rem Windows 下的 Gradle 启动脚本（精简版：直接用 java 跑 wrapper jar）。
@rem 官方完整版带一堆路径/编码处理，这个版本在常见环境下等价。
@rem ---------------------------------------------------------------------------
@if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.\
set WRAPPER_JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
  -Dorg.gradle.appname=gradlew ^
  -classpath "%WRAPPER_JAR%" ^
  org.gradle.wrapper.GradleWrapperMain %*

endlocal
