"""Spot checks of build_inventory.py output against facts read directly from the CardDemo sources.

    python3 migration/inventory/test_inventory.py
"""
import unittest

import build_inventory as bi

DS = "dataset:AWS.M2.CARDDEMO."


class InventoryFacts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = bi.build().to_json()
        cls.nodes = {n["id"]: n for n in data["nodes"]}
        cls.edges = {(e["from"], e["rel"], e["to"]) for e in data["edges"]}

    def has(self, src, rel, dst):
        self.assertIn((src, rel, dst), self.edges)

    def test_csd_transactions(self):
        # app/csd/CARDDEMO.CSD: TRANSACTION(CC00) PROGRAM(COSGN00C), CDV1 -> COCRDSEC
        self.has("transaction:CC00", "runs", "program:COSGN00C")
        self.has("transaction:CDV1", "runs", "program:COCRDSEC")
        self.assertEqual(self.nodes["program:COCRDSEC"]["type"], "missing-source")
        # app/app-vsam-mq/csd/CRDDEMOM.csd
        self.has("transaction:CDRA", "runs", "program:COACCT01")

    def test_menu_tables_resolve_to_xctl_targets(self):
        # COMEN02Y lists COPAUS0C as option 11; COADM02Y lists the Db2 COTRTLIC/COTRTUPC screens.
        self.has("program:COMEN01C", "xctl", "program:COPAUS0C")
        self.has("program:COADM01C", "xctl", "program:COTRTLIC")
        self.has("program:COSGN00C", "xctl", "program:COADM01C")

    def test_cics_file_access(self):
        # COUSR01C.cbl:240 EXEC CICS WRITE DATASET(WS-USRSEC-FILE); COBIL00C.cbl:379 REWRITE ACCTDAT
        self.has("program:COUSR01C", "write", "cics-file:USRSEC")
        self.has("program:COBIL00C", "update", "cics-file:ACCTDAT")
        self.has("program:COBIL00C", "write", "cics-file:TRANSACT")
        self.has("cics-file:CXACAIX", "dataset", DS + "CARDXREF.VSAM.AIX.PATH")

    def test_link_and_call(self):
        self.has("program:COPAUS1C", "link", "program:COPAUS2C")
        self.has("program:CBSTM03A", "call", "program:CBSTM03B")
        self.has("program:COTRN02C", "call", "program:CSUTLDTC")
        self.has("program:COBSWAIT", "call", "program:MVSWAIT")

    def test_batch_dd_binding_through_jcl(self):
        # POSTTRAN.jcl STEP15 CBTRN02C; CBTRN02C opens ACCTFILE I-O and TRANFILE OUTPUT.
        self.has("job:POSTTRAN", "runs", "program:CBTRN02C")
        self.has("program:CBTRN02C", "update", DS + "ACCTDATA.VSAM.KSDS")
        self.has("program:CBTRN02C", "write", DS + "TRANSACT.VSAM.KSDS")
        # CREASTMT runs CBSTM03A, which CALLs CBSTM03B, which owns the file I/O.
        self.has("program:CBSTM03B", "read", DS + "CUSTDATA.VSAM.KSDS")

    def test_proc_expansion_and_control_cards(self):
        # TRANREPT.jcl STEP05R EXEC PROC=REPROC; REPROC SYSIN is ctl/REPROCT (REPRO INFILE OUTFILE).
        self.has("job:TRANREPT", "read", DS + "TRANSACT.VSAM.KSDS")
        self.has("job:TRANREPT", "write", DS + "TRANSACT.BKUP")
        self.has("job:TRANREPT", "runs", "program:CBTRN03C")

    def test_online_submits_batch(self):
        # CORPT00C.cbl builds JCL with EXEC PROC=TRANREPT and writes it to TDQ JOBS (internal reader).
        self.has("program:CORPT00C", "submits", "proc:TRANREPT")
        self.has("program:CORPT00C", "write", "tdqueue:JOBS")

    def test_optional_module_resources(self):
        self.has("program:COTRTUPC", "write", "db2-table:TRANSACTION_TYPE")
        self.has("program:COPAUA0C", "write", "ims-segment:PAUTSUM0")
        self.has("program:COPAUA0C", "schedules", "psb:PSBPAUTB")
        self.has("job:CBPAUP0J", "runs", "program:CBPAUP0C")
        self.has("program:COACCT01", "uses", "mq-queue:CARD.DEMO.REPLY.ACCT")

    def test_listcat(self):
        acct = self.nodes[DS + "ACCTDATA.VSAM.KSDS"]
        self.assertEqual((acct["type"], acct["keylen"], acct["maxlrecl"]), ("KSDS", 11, 300))
        self.has(DS + "CARDDATA.VSAM.AIX", "base", DS + "CARDDATA.VSAM.KSDS")

    def test_every_source_program_has_a_slice(self):
        unsliced = [n["name"] for n in self.nodes.values()
                    if n["kind"] == "program" and "path" in n and "slice" not in n]
        self.assertEqual(unsliced, [])


if __name__ == "__main__":
    unittest.main()
