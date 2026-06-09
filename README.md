# Bearit Text Editor

Bearit is a high-performance Java 21 text editor designed specifically to handle massive file sizes, helping your system bear the heavy memory load.

The application is a zero-dependency text editor built from the ground up to handle large text files (exceeding 50GB) while maintaining a strict, lightweight memory footprint (under 4GB). 

By utilizing a custom virtual-paging architecture, background asynchronous preloading, and a dynamic LRU document cache, Bearit provides a seamless, infinite-scrolling experience for log files and datasets that would instantly crash traditional text editors.

## 🚀 Key Features

* **Massive File Support:** Open, view, and edit 50GB+ files instantly without `OutOfMemoryError` crashes.
* **Multi-Tabbed Interface:** Open and manage multiple massive files or scripts simultaneously in a clean, tabbed layout.
* **Integrated Hex Editor**: Instantly toggle any file into a high-performance Hex view with an interactive Data Inspector and offset jumping.
* **Asynchronous Paging:** Files are streamed from disk in 25MB chunks. Background threads preload adjacent chunks while scrolling, ensuring a stutter-free experience.
* **Global Search & Replace:** Perform file-wide "Find Next", "Find Previous", "Count Matches", and "Replace All" operations across gigabytes of data using memory-safe stream processing.
* **Persistent Undo/Redo:** An intelligent LRU cache keeps track of your recently edited chunks, allowing you to scroll away, scroll back, and still hit `Ctrl+Z` to undo your changes.
* **Zero Dependencies:** Built entirely with standard Java 21 NIO and Swing libraries. No external JARs or heavy frameworks are required.
* **Test Data Generator:** Includes a built-in tool to instantly spool up massive gigabyte-scale test files for benchmarking.
* **Native Dark Theme:** A fully integrated, system-level dark mode built entirely in pure Java Swing.
* **Custom Toolbar Tools:** Map external scripts or command-line operations to custom toolbar buttons (complete with custom icons and %rp relative path resolution).


---

## Demo

Here is a quick look at the application:

![Demo](https://github.com/Ed-Wares/Bearit/blob/main/DemoBearit.gif?raw=true)


## 📋 Requirements

To build and run Bearit, you will need:
* **Java Development Kit (JDK) 21** or higher. (only requirement for running Bearit)
* **Apache Maven** (3.6.0 or higher) for building the project.

---
## 🖱️ Setup & Installation (Without Command Line)

You do not need to use the terminal to use Bearit. Once you have the bearit.jar file, you can set it up to act like a native desktop application.

### Launching the App
Simply double-click the bearit*.jar file. As long as Java 21 is installed on your system, the graphical user interface will open immediately. You can drag and drop files directly from your file manager into the Bearit window to open them.


### Install the Right-Click Menu
For the best experience, you can integrate Bearit directly into your operating system's file manager so you can right-click any file and select "Edit with Bearit".

1. Open Bearit by double-clicking the JAR.

2. Navigate to the menu Help -> Install Context Menu.

3. Open File manager and right click on a text file.
   * Windows: This automatically creates a shortcut and adds Bearit to your Windows Explorer registry.

   * Linux (Ubuntu/MATE): This automatically generates the necessary shell scripts and installs them into your Nautilus or Caja file manager extensions.

4. You can easily remove this at any time by selecting Help -> Uninstall Context Menu.

### Linux Desktop Integration
When running on Linux, Bearit will automatically generate a Bearit.desktop launcher and a bearit executable bash script in the same folder as the JAR. You can copy the .desktop file to your ~/.local/share/applications/ folder to add Bearit to your system's main application launcher!

## 🛠️ How to Build

1. Clone or download the repository to your local machine.
2. Open a terminal and navigate to the root directory of the project (where the `pom.xml` is located).
3. Run the following Maven command to compile and package the application:

   ```bash
   mvn clean package
    ```
4. Once the build completes, the compiled executable will be located in the target/ directory as bearit-1.*.jar

---

## 💻 Command Line Usage

Bearit can be launched as a standard desktop application or controlled via terminal arguments.

### Basic Launch

Launch the application with a blank new document:

```bash
java -jar target/bearit-1.0.1.jar
```

### Open a File Directly

Pass a file path as an argument to instantly load it into the editor:

```bash
java -jar target/bearit-1.0.1.jar /path/to/your/largefile.log
```

### Options & Arguments

Bearit includes a built-in CLI parser for specialized tasks.

### Help
Display usage information:

```bash
java -jar target/bearit-1.*.jar -?
```

#### Generate Test Files
Use the `-g` flag to create a large test file filled with random data:

```bash
java -jar target/bearit-1.*.jar -g 10
```
This will create a 10GB file called bearit_test_file_10.00GB.txt for testing purposes.



## ⌨️ Keyboard Shortcuts

* Ctrl + F: Open Search & Replace Dialog

* Ctrl + G: Go To Line Number

* Ctrl + Z: Undo (within cached chunks)

* Ctrl + Y: Redo (within cached chunks)

* Ctrl + Home: Jump to the very beginning of the file

* Ctrl + End: Jump to the very end of the file

* Ctrl + Plus: Increase font size

* Ctrl + Minus: Decrease font size

* Ctrl + N: New File

* Ctrl + O: Open file

* Ctrl + S: Save file

* Ctrl + Shift + S: Save file as
