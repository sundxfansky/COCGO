# QQ login color fixtures

These PNGs retain 5 x 5 pixel patches from the tested Android 13 device at 1280 x 720, running CN game version 18.116.2. Everything outside the sampled controls is blacked out. The patches are copied from screenshots rather than generated from expected color constants.

- `consent.png`: initial agreement dialog, with both green buttons.
- `unchecked.png`: empty checkbox and disabled gray login buttons.
- `ready.png`: green checkmark and enabled blue QQ button.
- `waiting.png`: checked checkbox with disabled gray buttons while a login is in flight.
- `village.png`: negative sample after the village loads.

`QqLoginColorsTest` reads these as ordinary JVM images, so no Android bitmap mocks or OCR model are needed.
