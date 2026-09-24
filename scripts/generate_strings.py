#!/usr/bin/env python3
"""
Generates app/src/main/res/values/strings.xml (English) and values-it/strings.xml (Italian) from the
table below, so that both languages always have the same texts and the same placeholders.

Usage: python3 scripts/generate_strings.py
To add a language, add a column to the table and a write() call at the bottom.
"""
import re
import sys
from xml.sax.saxutils import escape

import os

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

# key: (english, italian). Plain text: escaping is done below.
S = {
    # Tabs
    "tab_photos": ("Photos", "Foto"),
    "tab_backup": ("Backup", "Backup"),
    "tab_settings": ("Settings", "Impostazioni"),
    # Actions
    "action_allow": ("Allow access", "Consenti l'accesso"),
    "action_back": ("Back", "Indietro"),
    "action_backup_now": ("Back up now", "Esegui backup ora"),
    "action_cancel": ("Cancel", "Annulla"),
    "action_change": ("Change", "Cambia"),
    "action_close": ("Close", "Chiudi"),
    "action_configure": ("Set up", "Configura"),
    "action_confirm": ("Confirm", "Conferma"),
    "action_continue": ("Continue", "Continua"),
    "action_edit": ("Edit", "Modifica"),
    "action_free_space": ("Free up space", "Libera spazio"),
    "action_hide_password": ("Hide password", "Nascondi password"),
    "action_info": ("Details", "Dettagli"),
    "action_play": ("Play", "Riproduci"),
    "action_refresh": ("Refresh", "Aggiorna"),
    "action_reset": ("Reset", "Azzera"),
    "action_retry": ("Try again", "Riprova"),
    "action_save": ("Save", "Salva"),
    "action_save_anyway": ("Save anyway", "Salva comunque"),
    "action_share": ("Share", "Condividi"),
    "action_show_password": ("Show password", "Mostra password"),
    "action_skip_for_now": ("Skip for now", "Salta per ora"),
    "action_start": ("Get started", "Inizia"),
    "action_stop": ("Stop", "Interrompi"),
    "action_test": ("Test connection", "Prova connessione"),
    # Backup tab
    "auto_backup_off": ("Off: the backup runs only when you tap “Back up now”.",
                        "Disattivato: il backup parte solo quando tocchi “Esegui backup ora”."),
    "backup_last_error": ("Last backup %1$s: %2$s", "Ultimo backup %1$s: %2$s"),
    "backup_never": ("No backup yet.", "Nessun backup ancora."),
    "backup_no_server": ("No server yet", "Nessun server"),
    "backup_no_server_text": ("Tell Scrigno where to save your photos.", "Indica a Scrigno dove salvare le tue foto."),
    "backup_running_progress": ("File %1$d of %2$d", "File %1$d di %2$d"),
    "backup_running_title": ("Backup in progress…", "Backup in corso…"),
    "backup_stat_on_phone": ("on the phone", "sul telefono"),
    "backup_stat_pending": ("to back up", "da salvare"),
    "backup_stat_safe": ("safe on the server", "al sicuro sul server"),
    "backup_waiting_network": ("Waiting for a network connection…", "In attesa di una connessione…"),
    # Notification channels
    "channel_alerts": ("Reminders and errors", "Promemoria ed errori"),
    "channel_progress": ("Backup progress", "Avanzamento del backup"),
    # Confirmations
    "confirm_delete_device_title": ("Delete from the phone?", "Eliminare dal telefono?"),
    "confirm_delete_device_text": ("This photo is not on your server: it will be gone for good.",
                                   "Questa foto non è sul tuo server: sarà eliminata definitivamente."),
    "confirm_delete_everywhere_title": ("Delete everywhere?", "Eliminare ovunque?"),
    "confirm_delete_everywhere_text": ("The photo will be deleted from the phone and from your server. This cannot be undone.",
                                       "La foto sarà eliminata dal telefono e dal tuo server. Non si può annullare."),
    "confirm_delete_server_title": ("Delete from the server?", "Eliminare dal server?"),
    "confirm_delete_server_text": ("This photo is only on your server: it will be deleted for good.",
                                   "Questa foto è solo sul tuo server: sarà eliminata definitivamente."),
    "confirm_remove_from_phone_title": ("Remove from the phone?", "Rimuovere dal telefono?"),
    "confirm_remove_from_phone_text": ("The photo stays on your server and in the gallery: it is downloaded when you open it.",
                                       "La foto resta sul tuo server e nella galleria: viene scaricata quando la apri."),
    # Errors
    "error_auth": ("Wrong user name or password, or no permission on the folder.",
                   "Nome utente o password errati, oppure permessi insufficienti sulla cartella."),
    "error_generic": ("Something went wrong: %1$s", "Qualcosa è andato storto: %1$s"),
    "error_identity_changed": (
        "The identity of the server has changed (expected %1$s, found %2$s). If you reinstalled the server, "
        "forget the old fingerprint in the server settings; otherwise someone could be intercepting the connection.",
        "L'identità del server è cambiata (attesa %1$s, trovata %2$s). Se hai reinstallato il server, "
        "dimentica la vecchia impronta nelle impostazioni del server; altrimenti qualcuno potrebbe intercettare la connessione."),
    "error_local_read": ("Cannot read %1$s on the phone.", "Impossibile leggere %1$s sul telefono."),
    "error_no_app": ("No app can open this file.", "Nessuna app può aprire questo file."),
    "error_no_permission": ("Scrigno has no access to the photos.", "Scrigno non ha accesso alle foto."),
    "error_not_found": ("The file is no longer on the server.", "Il file non è più sul server."),
    "error_other_server": ("This photo was saved on another server. Set that server up again to open it.",
                           "Questa foto è stata salvata su un altro server. Configura di nuovo quel server per aprirla."),
    "error_timeout": ("The server did not answer in time.", "Il server non ha risposto in tempo."),
    "error_unknown_host": ("Server not found: check the address (IP or name).",
                           "Server non trovato: controlla l'indirizzo (IP o nome)."),
    "error_unreachable": ("The server cannot be reached: is it on, and are you on the right network?",
                          "Il server non è raggiungibile: è acceso e sei sulla rete giusta?"),
    "error_untrusted_certificate": (
        "The server certificate is not signed by a known authority (%1$s). If it is your self-signed certificate, "
        "enable “Accept self-signed certificate”.",
        "Il certificato del server non è firmato da un'autorità nota (%1$s). Se è il tuo certificato "
        "autofirmato, attiva “Accetta certificato autofirmato”."),
    # Gallery filters
    "filter_all": ("All photos", "Tutte le foto"),
    "filter_not_backed_up": ("Not backed up yet", "Non ancora salvate"),
    "filter_on_device": ("On the phone", "Sul telefono"),
    "filter_server_only": ("Only on the server", "Solo sul server"),
    # Folders
    "folders_all_summary": ("Back up every photo and video of the phone", "Salva ogni foto e video del telefono"),
    # Free up space
    "free_space_computing": ("Calculating…", "Calcolo in corso…"),
    "free_space_confirm_title": ("Free up space?", "Liberare spazio?"),
    "free_space_description": (
        "Photos already safe on your server can leave the phone: they stay in the gallery as small previews "
        "and are downloaded when you open them.",
        "Le foto già al sicuro sul tuo server possono lasciare il telefono: restano nella galleria come "
        "piccole anteprime e si scaricano quando le apri."),
    "free_space_done": ("%1$s freed up", "Liberati %1$s"),
    "free_space_nothing": ("Nothing to remove right now.", "Niente da rimuovere per ora."),
    "free_space_nothing_verified": ("The files could not be verified on the server: nothing was removed.",
                                    "Non è stato possibile verificare i file sul server: non è stato rimosso nulla."),
    "free_space_off": ("Cleanup is off: everything stays on the phone.", "La pulizia è disattivata: tutto resta sul telefono."),
    "free_space_title": ("Free up space", "Libera spazio"),
    "free_space_verifying": ("Checking on the server: %1$d of %2$d", "Verifica sul server: %1$d di %2$d"),
    "free_space_waiting_confirmation": ("Waiting for your confirmation…", "In attesa della tua conferma…"),
    # Gallery
    "gallery_empty_filter": ("No photos match this filter.", "Nessuna foto corrisponde a questo filtro."),
    "gallery_empty_text": ("The photos you take will appear here.", "Le foto che scatti appariranno qui."),
    "gallery_empty_title": ("No photos", "Nessuna foto"),
    "gallery_filter": ("Filter", "Filtro"),
    "gallery_partial_access": ("You gave access only to some photos: the others will not be backed up.",
                               "Hai concesso l'accesso solo ad alcune foto: le altre non verranno salvate."),
    "gallery_permission_text": (
        "Scrigno needs to read your photos and videos to show them and back them up to your server. They never go anywhere else.",
        "Scrigno deve leggere foto e video per mostrarli e salvarli sul tuo server. Non vanno mai da nessun'altra parte."),
    "gallery_permission_title": ("Allow access to photos", "Consenti l'accesso alle foto"),
    # Protocol hints
    "hint_ftp": ("Plain FTP, not encrypted: use it only on your home network.",
                 "FTP semplice, senza cifratura: usalo solo nella rete di casa."),
    "hint_ftps": ("FTP encrypted with TLS (explicit, “FTPES”). With a self-signed certificate, enable the option below.",
                  "FTP cifrato con TLS (esplicito, “FTPES”). Con un certificato autofirmato attiva l'opzione qui sotto."),
    "hint_sftp": ("Recommended: encrypted, works with any Linux server, NAS (Synology, QNAP, TrueNAS…) or Raspberry Pi with SSH.",
                  "Consigliato: cifrato, funziona con qualsiasi server Linux, NAS (Synology, QNAP, TrueNAS…) o Raspberry Pi con SSH."),
    "hint_smb": ("Windows shared folders, Samba and most NAS. Enter the name of the share.",
                 "Cartelle condivise di Windows, Samba e la maggior parte dei NAS. Indica il nome della condivisione."),
    "hint_webdav": ("WebDAV without encryption: only for the home network.", "WebDAV senza cifratura: solo per la rete di casa."),
    "hint_webdavs": ("Nextcloud, ownCloud, Synology, Apache, nginx… For Nextcloud the folder is /remote.php/dav/files/USER",
                     "Nextcloud, ownCloud, Synology, Apache, nginx… Per Nextcloud la cartella è /remote.php/dav/files/UTENTE"),
    # Photo details
    "info_date": ("Date", "Data"),
    "info_resolution": ("Resolution", "Risoluzione"),
    "info_server_path": ("On the server", "Sul server"),
    "info_size": ("Size", "Dimensione"),
    "info_where": ("Where", "Dove"),
    # Intervals
    "interval_daily": ("Every day", "Ogni giorno"),
    "interval_weekly": ("Every week", "Ogni settimana"),
    # Keep on phone
    "keep_forever": ("Everything (never remove)", "Tutto (non rimuovere mai)"),
    "keep_none": ("Nothing: remove as soon as backed up", "Niente: rimuovi appena salvate"),
    # Locations
    "location_both": ("On the phone and on the server", "Sul telefono e sul server"),
    "location_device": ("Only on the phone (not backed up yet)", "Solo sul telefono (non ancora salvata)"),
    "location_server": ("Only on the server", "Solo sul server"),
    # Notifications
    "notification_backup_done_title": ("Backup completed", "Backup completato"),
    "notification_backup_failed_title": ("Backup failed", "Backup non riuscito"),
    "notification_backup_preparing": ("Preparing…", "Preparazione…"),
    "notification_backup_progress": ("%1$d of %2$d", "%1$d di %2$d"),
    "notification_backup_title": ("Backing up your photos", "Backup delle tue foto"),
    "notification_free_space_title": ("You can free up space", "Puoi liberare spazio"),
    # Onboarding
    "onboarding_backup_text": (
        "New photos go to your server by themselves, right after you take them and every night. More free space: "
        "photos stay in your gallery and are downloaded when you open them.",
        "Le foto nuove vanno da sole sul tuo server, subito dopo lo scatto e ogni notte. Più spazio libero: "
        "le foto restano nella galleria e si scaricano quando le apri."),
    "onboarding_backup_title": ("Like the big clouds, but yours", "Come i grandi cloud, ma tuo"),
    "onboarding_notifications_text": (
        "Notifications show the backup progress and remind you when you can free up space.",
        "Le notifiche mostrano l'avanzamento del backup e ti ricordano quando puoi liberare spazio."),
    "onboarding_permission_granted": ("Access granted", "Accesso consentito"),
    "onboarding_permission_text": ("Scrigno reads your photos and videos only to show them and send them to your server.",
                                   "Scrigno legge foto e video solo per mostrarli e inviarli al tuo server."),
    "onboarding_permission_title": ("Access to photos", "Accesso alle foto"),
    "onboarding_private_text": ("No account, no ads, no tracking. Nothing is ever sent to anyone but your server.",
                                "Nessun account, nessuna pubblicità, nessun tracciamento. Niente viene mai inviato a nessuno tranne il tuo server."),
    "onboarding_private_title": ("Anonymous and private", "Anonima e privata"),
    "onboarding_server_text": ("FTP, FTPS, SFTP, SMB (Samba) or WebDAV, reachable by IP address or domain name.",
                               "FTP, FTPS, SFTP, SMB (Samba) o WebDAV, raggiungibile con indirizzo IP o nome di dominio."),
    "onboarding_server_title": ("Your own server", "Il tuo server"),
    "onboarding_tagline": ("Your photos. On your server.", "Le tue foto. Sul tuo server."),
    "privacy_short": ("Scrigno has no account and collects no data: it only talks to your server.",
                      "Scrigno non ha account e non raccoglie dati: parla solo con il tuo server."),
    # Reset
    "reset_text": (
        "Scrigno forgets the server, the password, the settings and the list of backed up photos. "
        "The files on your server and on the phone are not touched.",
        "Scrigno dimentica server, password, impostazioni e l'elenco delle foto salvate. "
        "I file sul tuo server e sul telefono non vengono toccati."),
    "reset_title": ("Reset Scrigno?", "Azzerare Scrigno?"),
    # Schedule summary
    "schedule_any_network": ("any network", "qualsiasi rete"),
    "schedule_at_hour": ("at %1$s", "alle %1$s"),
    "schedule_charging": ("while charging", "in carica"),
    "schedule_instant": ("New photos right away", "Foto nuove subito"),
    "schedule_wifi_only": ("Wi‑Fi only", "solo Wi‑Fi"),
    # Settings sections
    "section_about": ("About", "Informazioni"),
    "section_advanced": ("Advanced", "Avanzate"),
    "section_backup": ("Backup", "Backup"),
    "section_server": ("Server", "Server"),
    "section_space": ("Space on the phone", "Spazio sul telefono"),
    # Server screen
    "server_domain": ("Domain or workgroup (optional)", "Dominio o gruppo di lavoro (facoltativo)"),
    "server_fingerprint": ("Server fingerprint", "Impronta del server"),
    "server_fingerprint_forget": ("Forget it (trust the next one)", "Dimenticala (fidati della prossima)"),
    "server_folder": ("Folder on the server", "Cartella sul server"),
    "server_folder_help": ("Created if missing, with a sub-folder for this phone.",
                           "Viene creata se manca, con una sottocartella per questo telefono."),
    "server_folder_placeholder": ("/srv/photos", "/srv/foto"),
    "server_folder_placeholder_smb": ("Photos", "Foto"),
    "server_folder_placeholder_webdav": ("/remote.php/dav/files/user/Photos", "/remote.php/dav/files/utente/Foto"),
    "server_host": ("Server address", "Indirizzo del server"),
    "server_host_help": ("IP address (192.168.1.10) or name (nas.example.com). You can paste a full URL too.",
                         "Indirizzo IP (192.168.1.10) o nome (nas.esempio.it). Puoi anche incollare un URL completo."),
    "server_host_placeholder": ("192.168.1.10", "192.168.1.10"),
    "server_intro": (
        "Where should Scrigno save your photos? Enter the details of your server: they stay only on this phone, with the password encrypted.",
        "Dove deve salvare le tue foto Scrigno? Inserisci i dati del tuo server: restano solo su questo telefono, con la password cifrata."),
    "server_password": ("Password", "Password"),
    "server_port": ("Port", "Porta"),
    "server_protocol": ("Protocol", "Protocollo"),
    "server_self_signed": ("Accept self-signed certificate", "Accetta certificato autofirmato"),
    "server_self_signed_help": ("The certificate is remembered on the first connection: any later change is refused.",
                                "Il certificato viene memorizzato alla prima connessione: ogni cambiamento successivo viene rifiutato."),
    "server_share": ("Share name", "Nome della condivisione"),
    "server_share_placeholder": ("photos", "foto"),
    "server_test_new_fingerprint": (
        "Server fingerprint, remembered from now on:\n%1$s\nTo be sure, compare it with the one shown by your server.",
        "Impronta del server, memorizzata da ora in poi:\n%1$s\nPer sicurezza confrontala con quella mostrata dal tuo server."),
    "server_test_ok": ("It works: Scrigno logged in and can write in the folder.",
                       "Funziona: Scrigno ha effettuato l'accesso e può scrivere nella cartella."),
    "server_testing": ("Connecting…", "Connessione in corso…"),
    "server_title": ("Your server", "Il tuo server"),
    "server_unencrypted_warning": (
        "This protocol is not encrypted: password and photos travel in the clear. Use it only on your home network (or through a VPN).",
        "Questo protocollo non è cifrato: password e foto viaggiano in chiaro. Usalo solo nella rete di casa (o tramite VPN)."),
    "server_username": ("User name", "Nome utente"),
    # Settings
    "setting_about": ("About Scrigno", "Informazioni su Scrigno"),
    "setting_about_summary": ("Version, license, privacy", "Versione, licenza, privacy"),
    "setting_auto_backup": ("Automatic backup", "Backup automatico"),
    "setting_auto_backup_summary": ("Back up new photos and videos on a schedule", "Salva in automatico foto e video nuovi"),
    "setting_cache": ("Cache of downloaded photos", "Cache delle foto scaricate"),
    "setting_cache_summary": ("%1$s used of %2$s", "%1$s usati su %2$s"),
    "setting_charging_only": ("Only while charging", "Solo in carica"),
    "setting_charging_only_summary": ("Save battery: wait for the charger", "Risparmia batteria: aspetta il caricatore"),
    "setting_clear_cache": ("Clear cache", "Svuota cache"),
    "setting_clear_cache_summary": ("Delete the photos downloaded for viewing (they stay on the server)",
                                    "Elimina le foto scaricate per la visione (restano sul server)"),
    "setting_device_folder": ("Name of this phone on the server", "Nome di questo telefono sul server"),
    "setting_device_folder_help": ("Photos go to <folder>/<this name>/<year>/<month>. Use a different name for each phone.",
                                   "Le foto vanno in <cartella>/<questo nome>/<anno>/<mese>. Usa un nome diverso per ogni telefono."),
    "setting_folders": ("Folders to back up", "Cartelle da salvare"),
    "setting_folders_all": ("All folders", "Tutte le cartelle"),
    "setting_hour": ("Preferred time", "Orario preferito"),
    "setting_instant": ("Back up new photos right away", "Salva subito le foto nuove"),
    "setting_instant_summary": ("Shortly after you take a photo or a video, besides the scheduled backup",
                                "Poco dopo lo scatto di una foto o di un video, oltre al backup programmato"),
    "setting_hour_not_used": ("Used for daily or weekly backups", "Usato per i backup giornalieri o settimanali"),
    "setting_interval": ("Frequency", "Frequenza"),
    "setting_keep_on_phone": ("Keep on the phone", "Tieni sul telefono"),
    "setting_rebuild": ("Rebuild the list from the server", "Ricostruisci l'elenco dal server"),
    "setting_rebuild_summary": ("After reinstalling the app: finds the photos of this phone already on the server",
                                "Dopo aver reinstallato l'app: ritrova le foto di questo telefono già presenti sul server"),
    "setting_reminder": ("Remind me", "Ricordamelo"),
    "setting_reminder_summary": ("A notification when photos can leave the phone",
                                 "Una notifica quando delle foto possono lasciare il telefono"),
    "setting_reset": ("Reset the app", "Azzera l'app"),
    "setting_reset_summary": ("Forget server, password and settings", "Dimentica server, password e impostazioni"),
    "setting_server": ("Server", "Server"),
    "setting_videos": ("Videos too", "Anche i video"),
    "setting_videos_summary": ("Back up videos as well as photos", "Salva anche i video oltre alle foto"),
    "setting_wifi_only": ("Only on Wi‑Fi", "Solo con Wi‑Fi"),
    "setting_wifi_only_summary": ("Do not use mobile data for the automatic backup",
                                  "Non usare i dati mobili per il backup automatico"),
    # Time
    "time_just_now": ("just now", "adesso"),
    # Viewer
    "viewer_delete": ("Delete", "Elimina"),
    "viewer_delete_everywhere": ("Delete everywhere", "Elimina ovunque"),
    "viewer_delete_from_server": ("Delete from server", "Elimina dal server"),
    "viewer_deleted_from_server": ("Deleted from the server", "Eliminata dal server"),
    "viewer_downloading": ("Downloading from your server…", "Download dal tuo server…"),
    "viewer_not_verified": ("The copy on the server could not be verified: the photo stays on the phone.",
                            "Non è stato possibile verificare la copia sul server: la foto resta sul telefono."),
    "viewer_remove_from_phone": ("Remove from phone", "Rimuovi dal telefono"),
    "viewer_removed_from_phone": ("Removed from the phone: it is still on your server",
                                  "Rimossa dal telefono: è ancora sul tuo server"),
    "viewer_restore": ("Save to phone", "Salva sul telefono"),
    "viewer_restored": ("Saved in the phone gallery (Pictures/Scrigno)", "Salvata nella galleria del telefono (Pictures/Scrigno)"),
    # About
    "about_description": (
        "Scrigno backs up the photos and videos of your phone to a server you own — a NAS, a home computer, "
        "a Raspberry Pi, a rented server — instead of Google Photos or iCloud. It can free up space on the phone "
        "while keeping every photo in the gallery.",
        "Scrigno salva foto e video del telefono su un server tuo — un NAS, un computer di casa, un Raspberry Pi, "
        "un server in affitto — invece di Google Foto o iCloud. Può liberare spazio sul telefono mantenendo "
        "ogni foto nella galleria."),
    "about_libraries_title": ("Open source libraries", "Librerie open source"),
    "about_license": (
        "Scrigno is free software: you can redistribute it and/or modify it under the terms of the GNU General Public "
        "License, version 3 or later. It comes with ABSOLUTELY NO WARRANTY.",
        "Scrigno è software libero: puoi ridistribuirlo e/o modificarlo secondo i termini della GNU General Public "
        "License, versione 3 o successiva. Viene fornito SENZA ALCUNA GARANZIA."),
    "about_license_title": ("License", "Licenza"),
    "about_privacy": (
        "No account, no analytics, no ads, no crash reports. The app connects only to the server you configure. "
        "Settings and password (encrypted with the Android Keystore) stay on this phone and are excluded from Android backups.",
        "Nessun account, nessuna statistica, nessuna pubblicità, nessun rapporto di crash. L'app si collega solo al "
        "server che configuri. Impostazioni e password (cifrata con il Keystore di Android) restano su questo telefono e "
        "sono escluse dai backup di Android."),
    "about_privacy_title": ("Privacy", "Privacy"),
    "about_source_title": ("Source code", "Codice sorgente"),
    "about_version": ("Version %1$s", "Versione %1$s"),
}

# key: ((en_one, en_other), (it_one, it_other))
P = {
    "backup_last_ok": (("Last backup %1$s · %2$d new file", "Last backup %1$s · %2$d new files"),
                       ("Ultimo backup %1$s · %2$d nuovo file", "Ultimo backup %1$s · %2$d nuovi file")),
    "folder_items": (("%d item", "%d items"), ("%d elemento", "%d elementi")),
    "free_space_available": (("%1$d item (%2$s) can leave the phone.", "%1$d items (%2$s) can leave the phone."),
                             ("%1$d elemento (%2$s) può lasciare il telefono.", "%1$d elementi (%2$s) possono lasciare il telefono.")),
    "free_space_confirm_text": (
        ("%1$d photo or video (%2$s) will be removed from the phone after checking that it is complete on your server. "
         "You will still see it in the gallery.",
         "%1$d photos and videos (%2$s) will be removed from the phone after checking that they are complete on your "
         "server. You will still see them in the gallery."),
        ("%1$d foto o video (%2$s) sarà rimosso dal telefono dopo aver verificato che sia integro sul tuo server. "
         "Continuerai a vederlo nella galleria.",
         "%1$d foto e video (%2$s) saranno rimossi dal telefono dopo aver verificato che siano integri sul tuo server. "
         "Continuerai a vederli nella galleria.")),
    "interval_days": (("Every %d day", "Every %d days"), ("Ogni %d giorno", "Ogni %d giorni")),
    "interval_hours": (("Every %d hour", "Every %d hours"), ("Ogni %d ora", "Ogni %d ore")),
    "keep_days": (("The last %d day", "The last %d days"), ("L'ultimo %d giorno", "Gli ultimi %d giorni")),
    "notification_backup_done": (("%d new file saved on your server", "%d new files saved on your server"),
                                 ("%d nuovo file salvato sul tuo server", "%d nuovi file salvati sul tuo server")),
    "notification_free_space_text": (
        ("%1$d item (%2$s) is safe on your server. Tap to remove it from the phone.",
         "%1$d items (%2$s) are safe on your server. Tap to remove them from the phone."),
        ("%1$d elemento (%2$s) è al sicuro sul tuo server. Tocca per rimuoverlo dal telefono.",
         "%1$d elementi (%2$s) sono al sicuro sul tuo server. Tocca per rimuoverli dal telefono.")),
    "rebuild_done": (("%d photo found on the server", "%d photos found on the server"),
                     ("%d foto ritrovata sul server", "%d foto ritrovate sul server")),
    "setting_folders_some": (("%d folder", "%d folders"), ("%d cartella", "%d cartelle")),
}

NON_TRANSLATABLE = {
    "app_name": "Scrigno",
    "source_url": "https://github.com/BitJacker/iniziare",
}


def android_escape(text: str) -> str:
    text = escape(text)  # & < >
    text = text.replace("\\", "\\\\").replace("\n", "\\n")
    text = text.replace("'", "\\'").replace('"', '\\"')
    if text.startswith("@") or text.startswith("?"):
        text = "\\" + text
    return text


def placeholders(text: str):
    return sorted(re.findall(r"%\d*\$?[sd]", text))


def write(lang_index: int, path: str, header: str, include_fixed: bool):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', header, "<resources>"]
    if include_fixed:
        for key, value in NON_TRANSLATABLE.items():
            lines.append(f'    <string name="{key}" translatable="false">{android_escape(value)}</string>')
    for key in sorted(S):
        lines.append(f'    <string name="{key}">{android_escape(S[key][lang_index])}</string>')
    for key in sorted(P):
        one, other = P[key][lang_index]
        lines.append(f'    <plurals name="{key}">')
        lines.append(f'        <item quantity="one">{android_escape(one)}</item>')
        lines.append(f'        <item quantity="other">{android_escape(other)}</item>')
        lines.append("    </plurals>")
    lines.append("</resources>")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# Same placeholders in both languages, or the app would crash when formatting.
for key, (en, it) in S.items():
    assert placeholders(en) == placeholders(it), key
for key, ((en1, en2), (it1, it2)) in P.items():
    assert placeholders(en2) == placeholders(it2), key

write(0, f"{OUT}/values/strings.xml", "<!-- Generated by scripts/generate_strings.py: edit the table there. English (default). -->", True)
write(1, f"{OUT}/values-it/strings.xml", "<!-- Generato da scripts/generate_strings.py: modifica la tabella lì. Italiano. -->", False)
print(len(S), "strings,", len(P), "plurals")
