// Data cells for COSGN0A in app/bms/COSGN00.bms.
// BMS POS is the attribute byte, so col = POS column + 1.
// USERID POS=(19,43) -> row 19, col 44. ERRMSG POS=(23,1) -> row 23, col 2.

export const cosgnMap = {
  mapset: "COSGN00",
  name: "COSGN0A",
  fields: [
    {
      name: "TRNNAME",
      row: 1,
      col: 9,
      length: 4,
      attributes: ["ASKIP", "FSET", "NORM"],
      color: "BLUE",
    },
    {
      name: "SYSID",
      row: 3,
      col: 72,
      length: 8,
      attributes: ["FSET", "NORM", "PROT"],
      initial: "        ",
      color: "BLUE",
    },
    {
      name: "USERID",
      row: 19,
      col: 44,
      length: 8,
      attributes: ["FSET", "IC", "NORM", "UNPROT"],
      color: "GREEN",
    },
    {
      name: "PASSWD",
      row: 20,
      col: 44,
      length: 8,
      attributes: ["DRK", "FSET", "UNPROT"],
      initial: "________",
      color: "GREEN",
    },
    {
      name: "ERRMSG",
      row: 23,
      col: 2,
      length: 78,
      attributes: ["ASKIP", "BRT", "FSET"],
      color: "RED",
    },
  ],
  literals: [
    { row: 1, col: 2, text: "Tran :", color: "BLUE" },
    { row: 19, col: 30, text: "User ID     :", color: "TURQUOISE" },
    { row: 20, col: 30, text: "Password    :", color: "TURQUOISE" },
    { row: 24, col: 2, text: "ENTER=Sign-on  F3=Exit", color: "YELLOW" },
  ],
};

export const cosgnReply = {
  kind: "screen",
  turn: 1,
  screen: "signon",
  map: { mapset: "COSGN00", name: "COSGN0A" },
  fields: {
    TRNNAME: "CC00",
    PASSWD: "________",
    ERRMSG: "Please enter User ID",
  },
  colors: {
    TRNNAME: "BLUE",
    SYSID: "BLUE",
    USERID: "GREEN",
    PASSWD: "GREEN",
    ERRMSG: "RED",
  },
  protectedFields: ["TRNNAME", "SYSID", "ERRMSG"],
  hiddenFields: ["PASSWD"],
  cursor: "USERID",
  actions: [
    { name: "sign-on", key: "ENTER" },
    { name: "exit", key: "PF3" },
  ],
};
