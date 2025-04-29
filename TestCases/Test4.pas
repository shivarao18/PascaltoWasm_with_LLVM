program TestForLoopWithBreakAndContinue;
var
	I: Integer;
begin
	I := 0;
  while I < 10 do
  begin
    I := I + 1;
    IF I = 3 THEN
    BEGIN
        continue;
    END;
    IF I = 8 THEN
    BEGIN
        break;
    END;
    WRITELN(I);
  end;
end.