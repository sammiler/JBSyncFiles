# SyncFiles Plugin for IntelliJ IDEA

**SyncFiles** is a lightweight plugin for IntelliJ IDEA (including CLion) that simplifies syncing files and directories from GitHub repositories directly into your project. Whether you're pulling code snippets, templates, or entire folders, SyncFiles streamlines the process with a user-friendly interface and customizable settings.

## Features

- **GitHub File Sync**: Download individual files or entire directories from GitHub repositories (supports `raw.githubusercontent.com` URLs and `/tree/` links).
- **Customizable Mappings**: Configure source URLs and target paths to sync files to specific locations in your project.
- **Automatic Project Refresh**: Updates the IDE's file system after syncing to ensure changes are immediately visible.
- **Shortcut Support**: Set custom keyboard shortcuts (e.g., `Ctrl+Shift+S`) to trigger syncing from the settings panel.
- **Tool Window Integration**: Start syncing with a single click from the dedicated "SyncFiles" tool window on the right sidebar.
- **Cross-Platform**: Works seamlessly on Windows, macOS, and Linux.

## Installation

1. **From JetBrains Marketplace** (Recommended):
    - Go to `File > Settings > Plugins` in IntelliJ IDEA.
    - Search for `SyncFiles` in the Marketplace.
    - Click `Install` and restart the IDE.

2. **Manual Installation**:
    - Download the latest plugin ZIP from [GitHub Releases](https://github.com/sammiler/JBSyncFiles/releases).
    - In IntelliJ, go to `File > Settings > Plugins`, click the gear icon, and select `Install Plugin from Disk`.
    - Choose the ZIP file and restart the IDE.

## Usage

1. **Configure Sync Settings**:
    - Open `File > Settings > SyncFiles Settings`.
    - Add mappings by specifying:
        - **Source URL**: A GitHub file (e.g., `https://raw.githubusercontent.com/user/repo/main/file.txt`) or directory (e.g., `https://github.com/user/repo/tree/main/folder`).
        - **Target Path**: The destination in your project (relative to the project root or absolute).
    - Set a refresh interval (in milliseconds) for IDE load disk.
    - Assign a custom shortcut (e.g., `Ctrl+Shift+A`) to trigger syncing.

2. **Start Syncing**:
    - Open the `SyncFiles` tool window (right sidebar, labeled "SyncFiles").
    - Click the `Start Sync` button to download and sync all configured files/directories.
    - The IDE will refresh automatically, reflecting changes in the project.

3. **Trigger via Shortcut**:
    - Use your configured shortcut to sync files instantly (shortcut functionality requires additional binding, see [Future Improvements](#future-improvements)).

## Example

To sync a file from GitHub:
- Source URL: `https://raw.githubusercontent.com/user/repo/main/example.txt`
- Target Path: `src/example.txt`
- Result: `example.txt` is downloaded to `<project_root>/src/example.txt` and visible in the IDE after refresh.

To sync a directory:
- Source URL: `https://github.com/user/repo/tree/main/src`
- Target Path: `src`
- Result: The `src` folder is downloaded and merged into `<project_root>/src`.

## Requirements

- IntelliJ IDEA 2024.1 or later (tested up to 2024.1.7).
- Internet connection for fetching GitHub content.
- Write permissions in the project directory.

## Known Issues

- Shortcut triggering for sync is not yet fully implemented (settings panel supports shortcut configuration, but binding to actions is in progress).
- Occasional file index delays during project initialization (mitigated in version 1.0.5).

## Future Improvements

- Bind custom shortcuts to directly trigger sync actions.
- Support for other Git hosting platforms (e.g., GitLab, Bitbucket).
- Progress indicators for large directory downloads.
- Validation for conflicting file mappings.

## Contributing

We welcome contributions! Please:
1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/YourFeature`).
3. Submit a pull request with clear descriptions.

Report issues or suggest features on the [GitHub Issues page](https://github.com/sammiler/JBSyncFiles/issues).



## Contact

- Author: sammiler
- Email: sammilergood@gmail.com


---

Built with ❤️ for developers.