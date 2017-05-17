package com.openeio.microspec;

/**
 * Created by Ish on 04-02-2017.
 */

public class HexDataOwn {
    private static final String HEXES = "0123456789ABCDEF";
    private static final String HEX_INDICATOR = "0x";
    private static final String SPACE = " ";

    private HexDataOwn() {
    }

    public static String hexToString(byte[] data) {
        if(data == null) {
            return null;
        } else {
            StringBuilder hex = new StringBuilder(2 * data.length);

            for(int i = 0; i <= data.length - 1; ++i) {
                byte dataAtIndex = data[i];
                hex.append("0x");
                hex.append("0123456789ABCDEF".charAt((dataAtIndex & 240) >> 4)).append("0123456789ABCDEF".charAt(dataAtIndex & 15));
                hex.append(" ");
            }

            return hex.toString();
        }
    }

    public static byte[] stringTobytes(String hexString) {
        String stringProcessed = hexString.trim().replaceAll("0x", "");
        stringProcessed = stringProcessed.replaceAll("\\s+", "");
        byte[] data = new byte[stringProcessed.length() / 2];
        int i = 0;

        for(int j = 0; i <= stringProcessed.length() - 1; i += 2) {
            byte character = (byte)Integer.parseInt(stringProcessed.substring(i, i + 2), 16);
            data[j] = character;
            ++j;
        }

        return data;
    }

    public static String hex4digits(String id) {
        return id.length() == 1?"000" + id:(id.length() == 2?"00" + id:(id.length() == 3?"0" + id:id));
    }
}
