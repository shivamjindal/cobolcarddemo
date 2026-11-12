package com.carddemo.copybook;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopybookTest {

    private static String copybook(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("carddemo.legacy-app"), "cpy", name + ".cpy"));
    }

    @Test
    void accountRecordOffsetsMatchTheCopybook() throws IOException {
        Copybook account = Copybook.parse(copybook("CVACT01Y"));

        assertThat(account.recordName()).isEqualTo("ACCOUNT-RECORD");
        assertThat(account.length()).isEqualTo(300);
        assertThat(account.field("ACCT-CURR-BAL"))
                .isEqualTo(new Field("ACCT-CURR-BAL", 12, new Pic.Numeric(12, 2, true, Pic.Usage.DISPLAY)));
        assertThat(account.field("ACCT-OPEN-DATE").offset()).isEqualTo(48);
        assertThat(account.field("ACCT-GROUP-ID").offset()).isEqualTo(112);
    }

    @Test
    void groupItemsContributeNoBytesOfTheirOwn() throws IOException {
        Copybook balance = Copybook.parse(copybook("CVTRA01Y"));

        assertThat(balance.fields()).extracting(Field::name)
                .containsExactly("TRANCAT-ACCT-ID", "TRANCAT-TYPE-CD", "TRANCAT-CD", "TRAN-CAT-BAL", "FILLER");
        assertThat(balance.field("TRAN-CAT-BAL").offset()).isEqualTo(17);
    }

    @Test
    void binaryUsagesHaveMainframeStorageLengths() {
        assertThat(Pic.parse("S9(10)V99", Pic.Usage.COMP_3).storageLength()).isEqualTo(7);
        assertThat(Pic.parse("9(03)", Pic.Usage.COMP_3).storageLength()).isEqualTo(2);
        assertThat(Pic.parse("9(09)", Pic.Usage.COMP).storageLength()).isEqualTo(4);
        assertThat(Pic.parse("9(11)", Pic.Usage.COMP).storageLength()).isEqualTo(8);
    }

    @Test
    void rejectsLayoutsItCannotFlatten() throws IOException {
        String export = copybook("CVEXPORT");
        assertThatThrownBy(() -> Copybook.parse(export)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Pic.parse("-ZZZ,ZZZ,ZZZ.ZZ", Pic.Usage.DISPLAY)).isInstanceOf(IllegalArgumentException.class);
    }
}
