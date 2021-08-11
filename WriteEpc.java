import com.impinj.octane.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

import java.math.BigInteger;

public class WriteEpc implements TagReportListener, TagOpCompleteListener {

    static short EPC_OP_ID = 123;
    static short PC_BITS_OP_ID = 321;
    static int opSpecID = 1;
    static int outstanding = 0;
    static Random r = new Random();
    private ImpinjReader reader;

    // 新しいEPC
    static String nextEpc;

    static Boolean isBinary(String bin) {
        for (int i = 0; i < bin.length(); i++) {
            char c = bin.charAt(i);
            if (c != '0' && c != '1') {
                return false;
            }
        }
        return true;
    }

    static String inputElem(String name, int length) throws Exception  {
        Scanner scan = new Scanner(System.in);
        System.out.print(name + ": ");
        String elem = scan.next();
        // 2進数かどうか
        if (!isBinary(elem)) {
            throw new Exception("Invalid " + name + "! No binary!");
        }
        // 長さ制限
        if (elem.length() > length) {
            throw new Exception("Invalid " + name + "! Too long!");
        }
        // 足りない部分は0埋め
        int need = length - elem.length();
        for (int i = 0; i < need; i++) {
            elem = '0' + elem;
        }
        System.out.println("-> " + elem);
        return elem;
    }

    static String inputEpc() throws Exception {
        String epc = "";
        epc += inputElem("Header", 8); // Header
        epc += inputElem("Other", 13); // Other
        epc += inputElem("Env", 8); // Env
        epc += inputElem("ERI", 4); // ERI
        epc += inputElem("Logic", 7); // Logic
        epc += inputElem("K", 4); // K
        epc += inputElem("Kdash", 4); // Kdash
        epc += inputElem("GroupID", 32); // GroupID
        epc += inputElem("MBIT", 1); // MBIT
        epc += inputElem("Serial", 15); // Serial
        String hexEpc = new BigInteger(epc, 2).toString(16);
        // 長さが24未満なら0で埋める
        if (hexEpc.length() < 24) {
            int need = 24 - hexEpc.length();
            for (int i = 0; i < need; i++) {
                hexEpc = '0' + hexEpc;
            }
        }
        return hexEpc;
    }

    public static void main(String[] args) {
        try {
            WriteEpc epcWriter = new WriteEpc();
            epcWriter.run();
        } catch (Exception e) {
            System.out.println(e);
            return;
        }
    }

    void programEpc(String currentEpc, short currentPC, String newEpc)
            throws Exception {
        if ((currentEpc.length() % 4 != 0) || (newEpc.length() % 4 != 0)) {
            throw new Exception("EPCs must be a multiple of 16- bits: "
                    + currentEpc + "  " + newEpc);
        }

        if (outstanding > 0) {
            return;
        }

        System.out.println("Programming Tag ");
        System.out.println("   EPC " + currentEpc + " to " + newEpc);

        TagOpSequence seq = new TagOpSequence();
        seq.setOps(new ArrayList<TagOp>());
        seq.setExecutionCount((short) 1); // delete after one time
        seq.setState(SequenceState.Active);
        seq.setId(opSpecID++);

        seq.setTargetTag(new TargetTag());
        seq.getTargetTag().setBitPointer(BitPointers.Epc);
        seq.getTargetTag().setMemoryBank(MemoryBank.Epc);
        seq.getTargetTag().setData(currentEpc);

        TagWriteOp epcWrite = new TagWriteOp();
        epcWrite.Id = EPC_OP_ID;
        epcWrite.setMemoryBank(MemoryBank.Epc);
        epcWrite.setWordPointer(WordPointers.Epc);
        epcWrite.setData(TagData.fromHexString(newEpc));

        // add to the list
        seq.getOps().add(epcWrite);

        // have to program the PC bits if these are not the same
        if (currentEpc.length() != newEpc.length()) {
            // keep other PC bits the same.
            String currentPCString = PcBits.toHexString(currentPC);

            short newPC = PcBits.AdjustPcBits(currentPC,
                    (short) (newEpc.length() / 4));
            String newPCString = PcBits.toHexString(newPC);

            System.out.println("   PC bits to establish new length: "
                    + newPCString + " " + currentPCString);

            TagWriteOp pcWrite = new TagWriteOp();
            pcWrite.Id = PC_BITS_OP_ID;
            pcWrite.setMemoryBank(MemoryBank.Epc);
            pcWrite.setWordPointer(WordPointers.PcBits);

            pcWrite.setData(TagData.fromHexString(newPCString));
            seq.getOps().add(pcWrite);
        }

        outstanding++;
        reader.addOpSequence(seq);
    }

    void run() {

        try {
            String hostname = "192.168.11.135";

            reader = new ImpinjReader();

            // Connect
            System.out.println("Connecting to " + hostname);
            reader.connect(hostname);

            // Get the default settings
            Settings settings = reader.queryDefaultSettings();

            // just use a single antenna here
            settings.getAntennas().disableAll();
            settings.getAntennas().getAntenna((short) 1).setEnabled(true);

            // set session one so we see the tag only once every few seconds
            settings.getReport().setIncludeAntennaPortNumber(true);
            settings.setRfMode(1002);
            settings.setSearchMode(SearchMode.SingleTarget);
            settings.setSession(1);
            // turn these on so we have them always
            settings.getReport().setIncludePcBits(true);

            // Set periodic mode so we reset the tag and it shows up with its
            // new EPC
            settings.getAutoStart().setMode(AutoStartMode.Periodic);
            settings.getAutoStart().setPeriodInMs(2000);
            settings.getAutoStop().setMode(AutoStopMode.Duration);
            settings.getAutoStop().setDurationInMs(1000);

            // Apply the new settings
            reader.applySettings(settings);

            // set up listeners to hear stuff back from SDK
            reader.setTagReportListener(this);
            reader.setTagOpCompleteListener(this);

            // Start the reader
            reader.start();

            // System.out.println("Press Enter to exit.");
            // Scanner s = new Scanner(System.in);
            // s.nextLine();

            // System.out.println("Stopping  " + hostname);
            // reader.stop();

            // System.out.println("Disconnecting from " + hostname);
            // reader.disconnect();

            // System.out.println("Done");
        } catch (OctaneSdkException ex) {
            System.out.println(ex.getMessage());
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace(System.out);
        }
    }

    public void onTagReported(ImpinjReader reader, TagReport report) {
        List<Tag> tags = report.getTags();

        for (Tag t : tags) {
            // String newEpc = inputEpc();
            String newEpc;
            try {
                System.out.println(t.getEpc().toString());
                // // epcを2進数に変換
                // String bin = "";
                // String[] splited = t.getEpc().toString().split(" ");
                // for (int i = 0; i < splited.length; i++) {
                //     int dec = Integer.parseInt(splited[i], 16);
                //     bin = bin + Integer.toBinaryString(dec);
                // }
                // System.out.println(bin);
                newEpc = inputEpc();
            } catch (Exception e) {
                System.out.println("Failed to inputEpc: " + e.toString());
                return;
            }

            if (t.isPcBitsPresent()) {
                short pc = t.getPcBits();
                String currentEpc = t.getEpc().toHexString();

                try {
                    programEpc(currentEpc, pc, newEpc);
                } catch (Exception e) {
                    System.out.println("Failed To program EPC: " + e.toString());
                }
            }
        }
    }

    public void onTagOpComplete(ImpinjReader reader, TagOpReport results) {
        System.out.println("TagOpComplete: ");
        for (TagOpResult t : results.getResults()) {
            System.out.print("  EPC: " + t.getTag().getEpc().toHexString());
            if (t instanceof TagWriteOpResult) {
                TagWriteOpResult tr = (TagWriteOpResult) t;

                if (tr.getOpId() == EPC_OP_ID) {
                    System.out.print("  Write to EPC Complete: ");
                } else if (tr.getOpId() == PC_BITS_OP_ID) {
                    System.out.print("  Write to PC Complete: ");
                }
                System.out.println(" result: " + tr.getResult().toString()
                        + " words_written: " + tr.getNumWordsWritten());
                outstanding--;
            }
        }
    }
}
