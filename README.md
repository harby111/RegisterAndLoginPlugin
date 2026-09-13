# Register And Login Plugin
Lightweight offline‑mode server registration & login plugin to prevent account theft on offline servers.

## ✨ Features
1. **Registration System**
Command: `/register <password> <confirmPassword>`
Unregistered players cannot move or chat, and may only run the register command. The two entered passwords must match. Players will be logged in automatically upon successful registration.

2. **Login System**
Command: `/login <password>`
Registered players join the server in a locked state, unable to move or chat. All restrictions will be lifted after entering the correct password.
OPs can run `/login update` to check for new versions on Modrinth. Tab‑completion for `update` is only visible to operators.

3. **Change Password**
Command: `/changepassword <oldPassword> <newPassword>`
Players must log in first before modifying their password.

4. **Persistent Data Storage**
Player accounts and passwords are saved at `plugins/RegisterAndLoginPlugin/accounts/PlayerUUID.txt`.
Account files use player UUID as filename, so accounts will not be lost even if players change their in‑game username. All data survives server restarts.

5. **Security Protection**
Players will be kicked if they fail to log in within 60 seconds after joining. Passwords are stored as SHA‑256 hashes instead of plain text.

6. **Customizable Messages**
All in‑game messages can be modified inside `lang.yml`.

## ⚙️ Supported Servers
✅ Paper 1.21.x
✅ Purpur 1.21.x
❌ Not compatible with Spigot, Fabric.

## 🎉 Version Changelog
v1.2.0: Added `/changepassword` command; Added OP‑only `/login update` version checker; Added tab‑completion for `/login update` (only visible to operators); Code optimization.

Planned features: 2‑day auto‑login for offline players.

## 📂 Open Source Information
This project is open‑sourced under the MIT License
Source Repository: https://github.com/chfengciyueaiwan/RegisterAndLoginPlugin
Feedback and pull requests are welcome to improve this plugin.
