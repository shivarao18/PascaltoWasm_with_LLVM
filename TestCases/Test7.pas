program TestFunctionWithParametersBONUS;

function Add(a, b: Integer): Integer;
begin
    Add := a + b;
end;

var
  X, Y, Result: Integer;

begin
    X := 5;
    Y := 10;
    Result := Add(X, Y);
    WRITELN(Result);
end.