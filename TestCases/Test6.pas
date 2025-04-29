program TestFunction;
function GetNumber: Integer;
begin
    WRITELN('Inside function!');
    GetNumber := 27;
end;

var
  Result: Integer;

begin
    Result := GetNumber();
    WRITELN(Result);
end.