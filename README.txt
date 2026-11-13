My House - WebView edition 3.0.0

The app is now only a frame around the device's own web page (the same screens as the website).
 - At home it finds the device by itself (saved address, smartlight.local, the device's own network, then a scan of the Wi-Fi) and shows the page the device serves.
 - Away from home it shows its own copy of the page and sends every request through the hidden Firebase link, so everything looks and works the same.
   A small "Remote" tag shows at the top while it is away. It checks every 12 seconds whether the home network is back and switches by itself.
 - The login/name, language and theme are shared between both modes, so you only sign in once.
 - Text selection, the blue selection box and the long-press menu are switched off (they stay on inside text fields).
 - File pickers work (firmware update, SD upload); file downloads are saved in Android/data/com.myhouse.app/files/Download.
 - Home only: firmware update and SD files (the hidden link does not carry them).
 - The old native screens and the home-screen widget were removed.
 - First time: connect the phone to the home Wi-Fi, open the app, choose Admin or Family and sign in. The app fetches the hidden-link settings by itself.
   If it cannot find the device, type the address shown on the OLED screen.
 - The page inside the app (assets/index.html) is a copy of the firmware's page. After a firmware update that changes the page, update the app too.
 - Not compiled or tested yet. If the GitHub build fails, send the last 40 lines of the red step.
