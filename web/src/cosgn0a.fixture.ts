// COSGN0A as GET /api/maps sends it. col is the attribute byte.
// USERID POS=(19,43). ERRMSG POS=(23,1). The parser paints one column to the right.

export const cosgnMap = {
  mapset: "COSGN00",
  name: "COSGN0A",
  fields: [
    {
      name: "TRNNAME",
      row: 1,
      col: 8,
      length: 4,
      attributes: ["ASKIP", "FSET", "NORM"],
      color: "BLUE",
    },
    {
      name: "SYSID",
      row: 3,
      col: 71,
      length: 8,
      attributes: ["FSET", "NORM", "PROT"],
      initial: "        ",
      color: "BLUE",
    },
    {
      name: "USERID",
      row: 19,
      col: 43,
      length: 8,
      attributes: ["FSET", "IC", "NORM", "UNPROT"],
      color: "GREEN",
    },
    {
      name: "PASSWD",
      row: 20,
      col: 43,
      length: 8,
      attributes: ["DRK", "FSET", "UNPROT"],
      initial: "________",
      color: "GREEN",
    },
    {
      name: "ERRMSG",
      row: 23,
      col: 1,
      length: 78,
      attributes: ["ASKIP", "BRT", "FSET"],
      color: "RED",
    },
  ],
  literals: [
    { row: 1, col: 1, length: 6, initial: "Tran :", color: "BLUE" },
    { row: 19, col: 29, length: 13, initial: "User ID     :", color: "TURQUOISE" },
    { row: 20, col: 29, length: 13, initial: "Password    :", color: "TURQUOISE" },
    { row: 24, col: 1, length: 22, initial: "ENTER=Sign-on  F3=Exit", color: "YELLOW" },
    { row: 19, col: 52, length: 0, initial: "", color: "GREEN" },
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
