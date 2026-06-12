' Cough Analyzer launcher (FFTT04D desktop app)
' Self-healing: locates the runnable JAR at launch time, so renaming or relocating the
' build artifact does NOT break the desktop icon. Runs windowless via javaw.
Option Explicit

Dim fso, shell, root, libs, jar, javaw
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

root = "C:\AndroidStudio\FFTT04D"
libs = root & "\desktop\build\libs"

' 1) Prefer the libs dir; 2) fall back to a project-wide search for the app jar.
jar = FindJar(libs)
If jar = "" Then jar = FindJarRecursive(root)

If jar = "" Then
    MsgBox "Cough Analyzer JAR not found under " & root & "." & vbCrLf & vbCrLf & _
           "Rebuild it with:" & vbCrLf & "   gradlew :desktop:fatJar", _
           vbExclamation, "Cough Analyzer"
    WScript.Quit 1
End If

' Prefer the JRE this machine has; fall back to javaw on PATH.
javaw = "C:\Program Files\Java\jre1.8.0_481\bin\javaw.exe"
If Not fso.FileExists(javaw) Then javaw = "javaw"

shell.CurrentDirectory = root
shell.Run """" & javaw & """ -jar """ & jar & """", 0, False

' --- helpers ---------------------------------------------------------------

' Exact CoughAnalyzer.jar wins; otherwise the largest jar > 1 MB (the fat jar),
' which skips thin/wrapper jars (gradle-wrapper.jar, desktop.jar, etc.).
Function FindJar(folder)
    FindJar = ""
    If Not fso.FolderExists(folder) Then Exit Function
    Dim f, bestSize
    bestSize = 1048576   ' 1 MB floor
    For Each f In fso.GetFolder(folder).Files
        If LCase(fso.GetExtensionName(f.Name)) = "jar" Then
            If LCase(f.Name) = "coughanalyzer.jar" Then
                FindJar = f.Path
                Exit Function
            End If
            If f.Size >= bestSize Then
                bestSize = f.Size
                FindJar = f.Path
            End If
        End If
    Next
End Function

Function FindJarRecursive(folder)
    FindJarRecursive = ""
    If Not fso.FolderExists(folder) Then Exit Function
    Dim found, subFolder
    found = FindJar(folder)
    If found <> "" Then
        FindJarRecursive = found
        Exit Function
    End If
    For Each subFolder In fso.GetFolder(folder).SubFolders
        found = FindJarRecursive(subFolder.Path)
        If found <> "" Then
            FindJarRecursive = found
            Exit Function
        End If
    Next
End Function
