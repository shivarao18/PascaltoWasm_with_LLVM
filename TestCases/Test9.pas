program TestWhileDemonstratingScope;
var
  I: Integer;
begin
  I := 1;
  while I < 20 do
  begin
    var I: Integer;
    I := 100;
    WRITELN(I);
    break;
  end;
  WRITELN(I);
end.