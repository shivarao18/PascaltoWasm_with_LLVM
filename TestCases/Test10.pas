program TestForDemonstratingScope;
var
  I: Integer;
begin
  for I := 1 to 10 do
  begin
    var I: Integer;
    I := 99;
    WRITELN(I);
  end;
end.