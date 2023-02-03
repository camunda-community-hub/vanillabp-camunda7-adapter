package io.vanillabp.camunda7.utils;

import java.util.regex.Pattern;

public class CaseUtils {

    private static final Pattern CAMEL_PATTERN = Pattern.compile("([a-z])([A-Z]+)");
    
    public static String camelToKebap(
            final String str) {
        
        if (str == null) {
            return null;
        }
        
        return CAMEL_PATTERN
                .matcher(str)
                .replaceAll("$1-$2")
                .toLowerCase();

    }
    
    public static String firstCharacterToUpperCase(
            final String str) {
        
        if (str == null) {
            return null;
        }
        
        final var result = new StringBuffer();
        if (str.length() > 0) {
            result.append(Character.toUpperCase(str.charAt(0)));
        }
        if (str.length() > 1) {
            result.append(str, 1, str.length());
        }
        
        return result.toString();
        
    }
    
}
