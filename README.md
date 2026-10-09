# Minecraft-server-forge-26-2

Minecraft Forge 26.2 server files and Hardcore Reset wrapper/source.

## Repository snapshot

The committed `world/` is the currently existing world created after the reset on October 9, 2026. It is **not** the deleted October 9 world and is **not** a recovery result. Recovery evidence is stored separately outside this repository.

Binary server dependencies, mods and world data use Git LFS. Run `git lfs install` and `git lfs pull` after cloning.

This is a full archival snapshot of the server directory, including the original `server.properties`, runtime logs, caches and lock files, explicitly requested by the owner. The configuration includes its original management secret. Pulling this repository does not rotate secrets; replace them manually before reuse when needed. `server.properties.example` remains available as a sanitized template.

The Hardcore Reset wrapper can permanently delete a world after a player death. Review `HARDCORE-RESET.md` and the wrapper before using it. This snapshot operation does not start the server.
