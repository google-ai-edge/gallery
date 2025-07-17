# **The Complete Guide to Capturing AI Edge Gallery Bug Reports for ANDROID devices**

Thank you for helping us improve the AI Edge Gallery app\! To find and fix bugs effectively, our engineers need detailed diagnostic information from your device. A **Full Bug Report** is the best way to provide this.

Please note that this guide is specifically for capturing bug reports on **Android devices**.

This guide covers the simple on-device method for all users and the more advanced `adb` method for developers.

### **Part 1: The Recommended Method (On Your Device)**

This is the fastest and easiest way to generate a complete bug report.

#### **1\. Enable Developer Options**

First, you need to enable the hidden "Developer options" menu on your phone.

* Open your phone's **Settings** app.  
* Scroll down and tap **"About phone"**.  
* Find the **"Build number"** and tap on it **7 times** in a row. You will see a "You are now a developer\!" message.

#### **2\. Capture the Bug Report**

It's best to capture the report **immediately after** you've experienced the bug.

* Go back to the main **Settings** page and find the new **"Developer options"** menu (it may be under "System").  
* Inside Developer options, tap **"Take bug report"**.  
* Select the **"Full report"** option and tap **"Report"**. This provides the most detailed information and is strongly preferred.

#### **3\. Wait and Share**

* Your phone will take a moment to collect all the data. When it's ready, a notification will appear saying **"Bug report captured"**.  
* Tap this notification.  
* The Android share menu will open. You can now share the `.zip` file with us. The easiest way is to **save it to your Google Drive** and share the link, or attach it directly to the GitHub issue.

### **Part 2: For Developers & Advanced Users (Using ADB)**

This section is for users comfortable with the Android Debug Bridge (`adb`) command-line tool.

#### **Capture a Bug Report Directly**

If you have a device connected to your computer with USB debugging enabled, you can use the following commands.

* **For a single connected device:**

```shell
# This saves the report to the specified path on your computer.
adb bugreport C:\Reports\MyBugReports
```

* **For multiple connected devices:**

```shell
# First, list devices to get the serial number.
adb devices

# Then, use the serial number to target the correct device.
adb -s <your_device_serial_number> bugreport
```

#### **Access Older Bug Reports from Your Device**

Android automatically saves recent bug reports on the device.

1. **List Saved Reports:**

```shell
adb shell ls /bugreports/
```

2. **Pull a Specific Report:**

```shell
adb pull /bugreports/<bug_report_filename.zip>
```

#### **Understanding the Bug Report File**

Your bug report is a `.zip` file. Inside, the most important file is **`bugreport-[...].txt`**. This text file contains the full system log (logcat), error logs (`dumpstate`), and detailed diagnostic output for all system services (`dumpsys`), giving engineers a complete picture of the device's state at the time of the bug.
