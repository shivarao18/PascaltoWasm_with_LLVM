program TestProcedureWithParameters;

procedure Add(a, b: Integer);
var
    Z: Integer;
begin
    Z := a+b;
    WRITELN(Z);
end;

var
  X, Y: Integer;
begin
    X := 5;
    Y := 10;
    Add(X, Y);
end.