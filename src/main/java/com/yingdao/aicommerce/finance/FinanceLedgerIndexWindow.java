package com.yingdao.aicommerce.finance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** An expiring operator window for pausing new long report calculations during a ledger index switch. */
final class FinanceLedgerIndexWindow {
    private static final Path MARKER=Path.of(System.getenv().getOrDefault(
        "APP_FINANCE_LEDGER_INDEX_WINDOW", "/run/heyi-ops/finance-ledger-index-window"));
    private static final long MAX_SECONDS=120;
    private FinanceLedgerIndexWindow(){}

    static boolean active(){return mode().equals("CUTOVER");}
    static boolean preparingIndex(){return !mode().isEmpty();}

    private static String mode() {
        if(!Files.isRegularFile(MARKER,java.nio.file.LinkOption.NOFOLLOW_LINKS))return "";
        try{
            if(Files.size(MARKER)>48)return "";
            var fields=Files.readString(MARKER).strip().split(" ",-1);
            if(fields.length!=2||(!fields[1].equals("CUTOVER")&&!fields[1].equals("PROJECTIONS")))return "";
            long remaining=Long.parseLong(fields[0])-Instant.now().getEpochSecond();
            return remaining>0&&remaining<=MAX_SECONDS?fields[1]:"";
        }catch(IOException|NumberFormatException unavailable){
            return "";
        }
    }
}
