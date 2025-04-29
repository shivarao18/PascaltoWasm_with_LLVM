program TestProcedureScope;

var
  A: Integer;

procedure ShowA;
begin
  var A: Integer;
  A := 999;
  WRITELN(A);
end;

begin
  A := 42;
  ShowA();
  WRITELN(A);
end.